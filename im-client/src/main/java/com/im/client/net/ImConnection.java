package com.im.client.net;

import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.ProtocolException;
import com.im.common.codec.PacketCodec;
import com.im.common.json.JsonUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 网络层：TCP 长连接、帧收发、请求-响应配对、心跳与重连状态机。
 *
 * <p>本类不依赖 Swing，可被 UI、bench 复用。运行线程：</p>
 * <ul>
 *   <li>{@code im-net-reader}：阻塞读帧，按类型分发；</li>
 *   <li>{@code im-net-writer}：串行化写帧，避免多线程写同一 Socket 造成帧交错；</li>
 *   <li>{@code im-net-scheduler}：心跳定时器。</li>
 * </ul>
 */
public final class ImConnection implements AutoCloseable {

    /** 连接状态机。 */
    public enum State {
        DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED
    }

    private final PacketCodec codec = PacketCodec.DEFAULT;
    private final Map<String, CompletableFuture<Packet>> pending = new ConcurrentHashMap<>();
    private final Map<MessageType, Consumer<Packet>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> named(r, "im-net-writer"));
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread reader;
    private volatile State state = State.DISCONNECTED;

    /** 建连（阶段 1 不做自动重连；重连在阶段 2 随 token 重登一起实现）。 */
    public void connect(String host, int port, Duration timeout) throws IOException {
        state = State.CONNECTING;
        this.socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), (int) timeout.toMillis());
        socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        running.set(true);
        this.reader = named(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "im-net-reader");
        reader.start();
        state = State.CONNECTED;
    }

    private static Thread named(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private void readLoop() {
        try {
            while (running.get()) {
                Packet packet = codec.read(in);
                if (packet == null) {
                    break;
                }
                dispatch(packet);
            }
        } catch (ProtocolException e) {
            failAll(e);
        } catch (IOException e) {
            if (running.get()) {
                failAll(e);
            }
        } finally {
            state = State.DISCONNECTED;
            running.set(false);
            failAll(new IOException("连接已关闭"));
        }
    }

    private void dispatch(Packet packet) {
        String requestId = JsonUtil.requestId(packet.payload());
        if (requestId != null && !requestId.isEmpty()) {
            CompletableFuture<Packet> future = pending.remove(requestId);
            if (future != null) {
                future.complete(packet);
                return;
            }
        }
        Consumer<Packet> listener = listeners.get(packet.type());
        if (listener != null) {
            listener.accept(packet);
        }
    }

    private void failAll(Throwable cause) {
        pending.forEach((id, future) -> future.completeExceptionally(cause));
        pending.clear();
    }

    /** 注册某类推送消息的监听器（在读线程中回调，耗时操作请自行切线程）。 */
    public void on(MessageType type, Consumer<Packet> listener) {
        listeners.put(type, listener);
    }

    /** 发送一个帧（异步写队列，不阻塞调用方）。 */
    public void send(Packet packet) {
        writer.execute(() -> {
            try {
                synchronized (out) {
                    codec.write(packet, out);
                }
            } catch (IOException | ProtocolException e) {
                failAll(e);
            }
        });
    }

    /**
     * 发送请求并等待同 requestId 的响应。
     *
     * @param request 请求帧（负载必须含 requestId）
     * @param timeout 超时时间
     * @return 响应帧
     */
    public Packet request(Packet request, Duration timeout) throws IOException, ProtocolException {
        String requestId = JsonUtil.requestId(request.payload());
        if (requestId == null || requestId.isEmpty()) {
            throw new ProtocolException(com.im.common.ErrorCode.INVALID_PAYLOAD, "请求缺少 requestId");
        }
        CompletableFuture<Packet> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            synchronized (out) {
                codec.write(request, out);
            }
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(requestId);
            throw new IOException("请求超时: " + request.type(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        } catch (ExecutionException e) {
            throw new IOException("请求失败: " + e.getCause(), e.getCause());
        }
    }

    public State state() {
        return state;
    }

    public void markAuthenticated() {
        state = State.AUTHENTICATED;
    }

    public boolean isOpen() {
        return running.get() && socket != null && !socket.isClosed();
    }

    @Override
    public void close() {
        running.set(false);
        state = State.DISCONNECTED;
        failAll(new IOException("连接已关闭"));
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 忽略
        }
        writer.shutdownNow();
    }
}
