package com.im.client.net;

import com.im.common.Packet;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 请求-响应管理器：{@code requestId → CompletableFuture}，默认 10 秒超时。
 *
 * <p>读线程收到带 requestId 的帧时调用 {@link #complete}；超时后 future 以
 * {@link TimeoutException} 异常完成，并从表中移除，避免泄漏。</p>
 */
public final class RequestManager implements AutoCloseable {

    private final Map<String, CompletableFuture<Packet>> pending = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Duration timeout;

    public RequestManager(Duration timeout) {
        this.timeout = timeout;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "im-request-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    public Duration timeout() {
        return timeout;
    }

    /** 登记一个请求，返回等待响应的 future。 */
    public CompletableFuture<Packet> register(String requestId) {
        CompletableFuture<Packet> future = new CompletableFuture<>();
        pending.put(requestId, future);
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            CompletableFuture<Packet> target = pending.remove(requestId);
            timers.remove(requestId);
            if (target != null) {
                target.completeExceptionally(new TimeoutException("请求超时: " + requestId));
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        timers.put(requestId, timer);
        return future;
    }

    /** 收到响应；返回 true 表示确实有等待中的请求。 */
    public boolean complete(String requestId, Packet packet) {
        CompletableFuture<Packet> future = pending.remove(requestId);
        ScheduledFuture<?> timer = timers.remove(requestId);
        if (timer != null) {
            timer.cancel(false);
        }
        if (future == null) {
            return false;
        }
        future.complete(packet);
        return true;
    }

    /** 连接断开时让所有等待中的请求失败。 */
    public void failAll(Throwable cause) {
        pending.forEach((id, future) -> future.completeExceptionally(cause));
        pending.clear();
        timers.values().forEach(timer -> timer.cancel(false));
        timers.clear();
    }

    public int pendingCount() {
        return pending.size();
    }

    @Override
    public void close() {
        failAll(new IllegalStateException("连接已关闭"));
        scheduler.shutdownNow();
    }
}
