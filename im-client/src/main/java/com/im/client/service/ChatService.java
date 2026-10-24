package com.im.client.service;

import com.im.client.net.ImConnection;
import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.ChatMessages.MsgAck;
import com.im.common.message.ChatMessages.PrivateMsg;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 聊天服务：发送私聊并跟踪待确认消息。
 *
 * <p>状态流转：{@code SENDING → SENT}（收到 MSG_ACK 且 code=0）或 {@code SENDING → FAILED}
 * （ACK 超时/发送异常）。重发复用原 {@code msgId}，服务端按 msgId 幂等，不会重复入库。</p>
 */
public final class ChatService implements AutoCloseable {

    /** 消息发送状态。 */
    public enum Status {
        SENDING, SENT, FAILED
    }

    /** 一条待跟踪的私聊消息。 */
    public static final class PendingMessage {
        private final String msgId;
        private final long toUserId;
        private final String content;
        private final long timestamp;
        private final List<Consumer<PendingMessage>> listeners = new CopyOnWriteArrayList<>();
        private volatile Status status = Status.SENDING;

        PendingMessage(String msgId, long toUserId, String content, long timestamp) {
            this.msgId = msgId;
            this.toUserId = toUserId;
            this.content = content;
            this.timestamp = timestamp;
        }

        public String msgId() {
            return msgId;
        }

        public long toUserId() {
            return toUserId;
        }

        public String content() {
            return content;
        }

        public long timestamp() {
            return timestamp;
        }

        public Status status() {
            return status;
        }

        /** 注册状态监听（在请求完成线程回调，UI 需自行切到 EDT）。 */
        public void onStatusChanged(Consumer<PendingMessage> listener) {
            listeners.add(listener);
        }

        void setStatus(Status next) {
            if (status == next) {
                return;
            }
            status = next;
            listeners.forEach(listener -> {
                try {
                    listener.accept(this);
                } catch (RuntimeException ignored) {
                    // 单个监听器异常不影响其它监听器
                }
            });
        }
    }

    private final ImConnection connection;
    private final long selfUserId;
    private final Duration ackTimeout;
    private final Map<String, PendingMessage> pending = new ConcurrentHashMap<>();
    private final List<Consumer<PrivateMsg>> incomingListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<com.im.common.message.ChatMessages.GroupMsg>> groupListeners =
            new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService timeoutScheduler;

    public ChatService(ImConnection connection, long selfUserId) {
        this(connection, selfUserId, Duration.ofSeconds(10));
    }

    public ChatService(ImConnection connection, long selfUserId, Duration ackTimeout) {
        this.connection = connection;
        this.selfUserId = selfUserId;
        this.ackTimeout = ackTimeout;
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "im-chat-ack-timeout");
            t.setDaemon(true);
            return t;
        });
        connection.on(MessageType.PRIVATE_MSG, packet -> {
            try {
                PrivateMsg msg = JsonUtil.fromJson(packet.payload(), PrivateMsg.class);
                incomingListeners.forEach(listener -> listener.accept(msg));
            } catch (Exception ignored) {
                // 推送解析失败不影响其它消息
            }
        });
        connection.on(MessageType.GROUP_MSG, packet -> {
            try {
                com.im.common.message.ChatMessages.GroupMsg msg =
                        JsonUtil.fromJson(packet.payload(), com.im.common.message.ChatMessages.GroupMsg.class);
                groupListeners.forEach(listener -> listener.accept(msg));
            } catch (Exception ignored) {
                // 忽略
            }
        });
    }

    /** 发送群消息；与私聊共享待确认跟踪。 */
    public CompletableFuture<PendingMessage> sendGroup(long groupId, String content) {
        PendingMessage message = newMessage(groupId, content);
        return doSendGroup(message);
    }

    private CompletableFuture<PendingMessage> doSendGroup(PendingMessage message) {
        if (!connection.isOpen()) {
            message.setStatus(Status.FAILED);
            return CompletableFuture.completedFuture(message);
        }
        pending.put(message.msgId(), message);
        String requestId = UUID.randomUUID().toString();
        Packet request = Packet.ofJson(MessageType.GROUP_MSG, JsonUtil.toJson(
                new com.im.common.message.ChatMessages.GroupMsg(requestId, message.msgId(), selfUserId,
                        message.toUserId(), message.content(), message.timestamp())));

        timeoutScheduler.schedule(() -> {
            PendingMessage current = pending.get(message.msgId());
            if (current != null && current.status() == Status.SENDING) {
                current.setStatus(Status.FAILED);
            }
        }, ackTimeout.toMillis(), TimeUnit.MILLISECONDS);

        return connection.request(request).handle((packet, error) -> {
            if (error != null) {
                message.setStatus(Status.FAILED);
                pending.remove(message.msgId());
                return message;
            }
            try {
                MsgAck ack = JsonUtil.fromJson(packet.payload(), MsgAck.class);
                message.setStatus(ack.code() == ErrorCode.OK.code() ? Status.SENT : Status.FAILED);
            } catch (Exception e) {
                message.setStatus(Status.FAILED);
            }
            pending.remove(message.msgId());
            return message;
        });
    }

    /** 注册收到群消息的回调。 */
    public Runnable onIncomingGroup(
            Consumer<com.im.common.message.ChatMessages.GroupMsg> listener) {
        groupListeners.add(listener);
        return () -> groupListeners.remove(listener);
    }

    /** 创建一条待发送消息（尚未发送，状态为 SENDING）。 */
    public PendingMessage newMessage(long toUserId, String content) {
        return new PendingMessage(UUID.randomUUID().toString(), toUserId, content, System.currentTimeMillis());
    }

    /** 用指定 msgId 创建消息（压测/重发对账使用）。 */
    public PendingMessage newMessage(long toUserId, String content, String msgId) {
        return new PendingMessage(msgId, toUserId, content, System.currentTimeMillis());
    }

    /** 发送私聊；返回的消息对象会在 ACK 到达或超时时更新状态。 */
    public CompletableFuture<PendingMessage> sendPrivate(long toUserId, String content) {
        return send(newMessage(toUserId, content));
    }

    /** 发送私聊（指定 msgId，服务端按 msgId 幂等）。 */
    public CompletableFuture<PendingMessage> sendPrivate(long toUserId, String content, String msgId) {
        return send(newMessage(toUserId, content, msgId));
    }

    /** 发送一个已创建的消息对象。 */
    public CompletableFuture<PendingMessage> send(PendingMessage message) {
        return doSend(message);
    }

    /** 重发：复用原 {@code msgId}，状态回到 SENDING。 */
    public CompletableFuture<PendingMessage> resend(PendingMessage message) {
        message.setStatus(Status.SENDING);
        return doSend(message);
    }

    private CompletableFuture<PendingMessage> doSend(PendingMessage message) {
        if (!connection.isOpen()) {
            message.setStatus(Status.FAILED);
            return CompletableFuture.completedFuture(message);
        }
        pending.put(message.msgId(), message);
        String requestId = UUID.randomUUID().toString();
        Packet request = Packet.ofJson(MessageType.PRIVATE_MSG, JsonUtil.toJson(
                new PrivateMsg(requestId, message.msgId(), selfUserId, message.toUserId(),
                        message.content(), message.timestamp())));

        // ACK 超时看门狗
        timeoutScheduler.schedule(() -> {
            PendingMessage current = pending.get(message.msgId());
            if (current != null && current.status() == Status.SENDING) {
                current.setStatus(Status.FAILED);
            }
        }, ackTimeout.toMillis(), TimeUnit.MILLISECONDS);

        return connection.request(request).handle((packet, error) -> {
            if (error != null) {
                message.setStatus(Status.FAILED);
                return message;
            }
            try {
                MsgAck ack = JsonUtil.fromJson(packet.payload(), MsgAck.class);
                message.setStatus(ack.code() == ErrorCode.OK.code() ? Status.SENT : Status.FAILED);
            } catch (Exception e) {
                message.setStatus(Status.FAILED);
            }
            pending.remove(message.msgId());
            return message;
        });
    }

    /** 注册收到私聊的回调（在读线程回调，UI 需切 EDT）。 */
    public Runnable onIncoming(Consumer<PrivateMsg> listener) {
        incomingListeners.add(listener);
        return () -> incomingListeners.remove(listener);
    }

    public int pendingCount() {
        return pending.size();
    }

    @Override
    public void close() {
        timeoutScheduler.shutdownNow();
    }
}
