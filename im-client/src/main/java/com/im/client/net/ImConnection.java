package com.im.client.net;

import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.ProtocolException;
import com.im.common.codec.PacketCodec;
import com.im.common.json.JsonUtil;
import com.im.common.message.SystemMessages.Ping;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 网络层：TCP 长连接、读线程、有界写队列线程、心跳、状态机与状态监听器。
 *
 * <p>不依赖 Swing，可被 UI、bench 复用。运行线程：</p>
 * <ul>
 *   <li>{@code im-net-reader}：阻塞读帧，配对请求或分发推送；</li>
 *   <li>{@code im-net-writer}：从有界队列串行写帧，避免帧交错；</li>
 *   <li>{@code im-net-heartbeat}：定时 PING，连续无响应判定断线。</li>
 * </ul>
 */
public final class ImConnection implements AutoCloseable {

    /** 连接状态机。 */
    public enum State {
        DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED
    }

    /** 写队列容量：满则说明网络阻塞，直接断开。 */
    private static final int OUTBOX_CAPACITY = 256;

    private final PacketCodec codec = PacketCodec.DEFAULT;
    private final EventDispatcher events = new EventDispatcher();
    private final RequestManager requests;
    private final BlockingQueue<Packet> outbox = new LinkedBlockingQueue<>(OUTBOX_CAPACITY);
    private final List<Consumer<State>> stateListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler;
    private final Duration heartbeatInterval;
    private final int heartbeatMissLimit;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread reader;
    private Thread writer;
    private volatile State state = State.DISCONNECTED;
    private volatile long lastInboundAt = System.currentTimeMillis();

    public ImConnection() {
        this(Duration.ofSeconds(10), Duration.ofSeconds(30), 3);
    }

    public ImConnection(Duration requestTimeout, Duration heartbeatInterval, int heartbeatMissLimit) {
        this.requests = new RequestManager(requestTimeout);
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeatMissLimit = heartbeatMissLimit;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "im-net-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    // ---------- 生命周期 ----------

    /** 建立连接并启动读/写/心跳线程。 */
    public void connect(String host, int port, Duration timeout) throws IOException {
        changeState(State.CONNECTING);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(0);
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.lastInboundAt = System.currentTimeMillis();
        running.set(true);

        this.reader = daemon(this::readLoop, "im-net-reader");
        this.writer = daemon(this::writeLoop, "im-net-writer");
        reader.start();
        writer.start();
        scheduler.scheduleAtFixedRate(this::heartbeat, heartbeatInterval.toMillis(),
                heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        changeState(State.CONNECTED);
    }

    private static Thread daemon(Runnable r, String name) {
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
                lastInboundAt = System.currentTimeMillis();
                dispatch(packet);
            }
        } catch (ProtocolException e) {
            failAll(e);
        } catch (IOException e) {
            if (running.get()) {
                failAll(e);
            }
        } finally {
            shutdown(false);
        }
    }

    private void dispatch(Packet packet) {
        String requestId = JsonUtil.requestId(packet.payload());
        if (requestId != null && !requestId.isEmpty() && requests.complete(requestId, packet)) {
            return;
        }
        events.dispatch(packet);
    }

    private void writeLoop() {
        try {
            while (running.get()) {
                Packet packet = outbox.poll(1, TimeUnit.SECONDS);
                if (packet == null) {
                    continue;
                }
                synchronized (out) {
                    codec.write(packet, out);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | ProtocolException e) {
            if (running.get()) {
                failAll(e);
            }
        }
    }

    private void heartbeat() {
        if (!running.get()) {
            return;
        }
        long idle = System.currentTimeMillis() - lastInboundAt;
        if (idle > heartbeatInterval.toMillis() * heartbeatMissLimit) {
            failAll(new IOException("心跳超时，连接已断开"));
            shutdown(true);
            return;
        }
        Packet ping = Packet.ofJson(MessageType.PING,
                JsonUtil.toJson(new Ping(UUID.randomUUID().toString(), System.currentTimeMillis())));
        outbox.offer(ping);
    }

    private void failAll(Throwable cause) {
        requests.failAll(cause);
    }

    private void shutdown(boolean notify) {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // 忽略
        }
        requests.failAll(new IOException("连接已关闭"));
        changeState(State.DISCONNECTED);
    }

    // ---------- 发送 ----------

    /** 投递一个帧到写队列；队列满视为连接不可用。 */
    public void send(Packet packet) {
        if (!running.get()) {
            throw new IllegalStateException("连接未建立");
        }
        if (!outbox.offer(packet)) {
            throw new IllegalStateException("发送队列已满");
        }
    }

    /** 发送请求并等待同 requestId 的响应（由 RequestManager 做 10s 超时）。 */
    public CompletableFuture<Packet> request(Packet packet) {
        String requestId = JsonUtil.requestId(packet.payload());
        if (requestId == null || requestId.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("请求缺少 requestId"));
        }
        CompletableFuture<Packet> future = requests.register(requestId);
        try {
            send(packet);
        } catch (RuntimeException e) {
            requests.failAll(e);
            return CompletableFuture.failedFuture(e);
        }
        return future;
    }

    // ---------- 监听 ----------

    /** 注册推送监听器，返回注销句柄。 */
    public Runnable on(MessageType type, Consumer<Packet> listener) {
        return events.on(type, listener);
    }

    /** 注册状态监听器（在读线程/心跳线程回调）。 */
    public void onState(Consumer<State> listener) {
        stateListeners.add(listener);
    }

    private void changeState(State next) {
        if (state == next) {
            return;
        }
        state = next;
        stateListeners.forEach(listener -> {
            try {
                listener.accept(next);
            } catch (RuntimeException ignored) {
                // 忽略监听器异常
            }
        });
    }

    /** 登录成功后由 service 层调用。 */
    public void markAuthenticated() {
        changeState(State.AUTHENTICATED);
    }

    public State state() {
        return state;
    }

    public boolean isOpen() {
        return running.get() && socket != null && !socket.isClosed();
    }

    @Override
    public void close() {
        shutdown(true);
        requests.close();
        scheduler.shutdownNow();
    }
}
