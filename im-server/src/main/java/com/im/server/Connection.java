package com.im.server;

import com.im.common.Packet;
import com.im.common.message.ChatMessages.OfflineMsgItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 一条客户端连接。
 *
 * <p>总纲要求：每个会话配一个有界发送队列 + 写线程，避免多线程同时写同一 Socket 导致帧交错，
 * 也避免慢客户端拖住读线程。队列满时判定为慢消费者并断开。</p>
 *
 * <p>运行线程：写线程 {@code im-writer-*}；读循环由 {@link ConnectionHandler} 所在线程执行。</p>
 */
public final class Connection {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);

    private static final Packet POISON = Packet.empty(com.im.common.MessageType.ERROR);

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final BlockingQueue<Packet> sendQueue;
    private final Thread writer;
    private final int sendQueueSize;

    private volatile long userId;
    private volatile String username = "";
    private volatile String token = "";
    private volatile boolean open = true;

    /** 离线推送状态（阶段 1 使用内存队列，阶段 2 换成数据库游标）。 */
    private final Deque<OfflineMsgItem> offlinePending = new ArrayDeque<>();
    private final Set<String> unacked = new HashSet<>();

    public Connection(Socket socket, int sendQueueSize) throws IOException {
        this.socket = socket;
        this.sendQueueSize = sendQueueSize;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.sendQueue = new LinkedBlockingQueue<>(sendQueueSize);
        this.writer = new Thread(this::writeLoop, "im-writer-" + socket.getPort());
        this.writer.setDaemon(true);
        this.writer.start();
    }

    private void writeLoop() {
        try {
            while (open) {
                Packet packet = sendQueue.poll(1, TimeUnit.SECONDS);
                if (packet == null) {
                    continue;
                }
                if (packet == POISON) {
                    break;
                }
                PacketCodecHolder.WRITE(packet, out);
            }
        } catch (Exception e) {
            if (open) {
                log.debug("写线程退出: {}", e.getMessage());
            }
        } finally {
            close();
        }
    }

    /** 发送一个帧；队列满说明客户端消费过慢，直接断开。 */
    public void send(Packet packet) {
        if (!open) {
            return;
        }
        if (!sendQueue.offer(packet)) {
            log.warn("发送队列已满（{}），断开慢消费者 {}", sendQueueSize, remoteAddress());
            close();
        }
    }

    /** 阻塞读取下一个帧；返回 null 表示对端正常关闭。 */
    public Packet read() throws IOException, com.im.common.ProtocolException {
        return PacketCodecHolder.READ(in);
    }

    public void close() {
        if (!open) {
            return;
        }
        open = false;
        sendQueue.offer(POISON);
        writer.interrupt();
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }

    public boolean isOpen() {
        return open && !socket.isClosed();
    }

    public long userId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String username() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String token() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Deque<OfflineMsgItem> offlinePending() {
        return offlinePending;
    }

    public Set<String> unacked() {
        return unacked;
    }

    public String remoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    /** 隔离对 codec 的直接依赖，便于之后替换实现。 */
    private static final class PacketCodecHolder {
        private static final com.im.common.codec.PacketCodec CODEC = com.im.common.codec.PacketCodec.DEFAULT;

        static void WRITE(Packet packet, DataOutputStream out) throws IOException, com.im.common.ProtocolException {
            CODEC.write(packet, out);
        }

        static Packet READ(DataInputStream in) throws IOException, com.im.common.ProtocolException {
            return CODEC.read(in);
        }
    }
}
