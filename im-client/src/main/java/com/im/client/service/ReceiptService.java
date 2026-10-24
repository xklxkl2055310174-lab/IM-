package com.im.client.service;

import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.ChatMessages.DeliverAck;
import com.im.common.message.ChatMessages.GroupMsg;
import com.im.common.message.ChatMessages.OfflineMsgItem;
import com.im.common.message.ChatMessages.OfflineMsgPush;
import com.im.common.message.ChatMessages.PrivateMsg;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 可靠接收：对收到的消息自动回 {@code DELIVER_ACK}。
 *
 * <p>规则（总纲）：收到 {@code PRIVATE_MSG}/{@code GROUP_MSG}/{@code OFFLINE_MSG_PUSH} 后
 * 自动确认；<b>200ms 内合并成一批</b>发送；按 {@code msgId} 去重，重复推送只确认一次。</p>
 */
public final class ReceiptService implements AutoCloseable {

    /** 去重窗口容量。 */
    private static final int SEEN_CAPACITY = 4096;

    private final Consumer<Packet> sender;
    private final Duration flushInterval;
    private final Map<String, Boolean> seen = new ConcurrentHashMap<>();
    private final List<String> queue = new ArrayList<>();
    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicInteger acks = new AtomicInteger();
    private volatile boolean flushScheduled;

    public ReceiptService(Consumer<Packet> sender, Duration flushInterval) {
        this.sender = sender;
        this.flushInterval = flushInterval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "im-receipt-flush");
            t.setDaemon(true);
            return t;
        });
    }

    public ReceiptService(Consumer<Packet> sender) {
        this(sender, Duration.ofMillis(200));
    }

    /** 处理一条推送；内部会去重并排队确认。 */
    public void accept(Packet packet) {
        switch (packet.type()) {
            case PRIVATE_MSG -> handlePrivate(packet);
            case GROUP_MSG -> handleGroup(packet);
            case OFFLINE_MSG_PUSH -> handleOffline(packet);
            default -> {
                // 其它类型不确认
            }
        }
    }

    private void handlePrivate(Packet packet) {
        try {
            enqueue(JsonUtil.fromJson(packet.payload(), PrivateMsg.class).msgId());
        } catch (Exception ignored) {
            // 解析失败不确认
        }
    }

    private void handleGroup(Packet packet) {
        try {
            enqueue(JsonUtil.fromJson(packet.payload(), GroupMsg.class).msgId());
        } catch (Exception ignored) {
            // 解析失败不确认
        }
    }

    private void handleOffline(Packet packet) {
        try {
            OfflineMsgPush push = JsonUtil.fromJson(packet.payload(), OfflineMsgPush.class);
            for (OfflineMsgItem item : push.messages()) {
                enqueue(item.msgId());
            }
        } catch (Exception ignored) {
            // 解析失败不确认
        }
    }

    /** 入队；首次入队时调度一次 flush。 */
    private void enqueue(String msgId) {
        if (msgId == null || msgId.isBlank()) {
            return;
        }
        if (seen.putIfAbsent(msgId, Boolean.TRUE) != null) {
            return; // 已确认过，去重
        }
        if (seen.size() > SEEN_CAPACITY) {
            // 简单老化：超过容量时清空重来（阶段 3-B 可接受）
            seen.clear();
            seen.put(msgId, Boolean.TRUE);
        }
        synchronized (lock) {
            queue.add(msgId);
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        scheduler.schedule(this::flush, flushInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void flush() {
        List<String> batch;
        synchronized (lock) {
            batch = new ArrayList<>(queue);
            queue.clear();
            flushScheduled = false;
        }
        if (batch.isEmpty()) {
            return;
        }
        flushes.incrementAndGet();
        for (String msgId : batch) {
            try {
                sender.accept(Packet.ofJson(MessageType.DELIVER_ACK,
                        JsonUtil.toJson(new DeliverAck(UUID.randomUUID().toString(), msgId))));
                acks.incrementAndGet();
            } catch (RuntimeException ignored) {
                // 连接断开时忽略，重连后离线消息会重推
            }
        }
    }

    /** 已发送的确认批次数（测试用）。 */
    public int flushCount() {
        return flushes.get();
    }

    /** 已发送的确认条数（测试用）。 */
    public int ackCount() {
        return acks.get();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
