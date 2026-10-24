package com.im.client.service;

import com.im.client.net.ImConnection;
import com.im.common.message.AccountMessages.ReloginResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 连接管理：断线后指数退避重连，并用 token 自动重登；失败则提示回登录页。
 *
 * <p>退避序列：1s、2s、4s、8s… 上限 30s；连续 10 次连不上则放弃（回登录页）。
 * 重登返回非 0（token 失效/被顶）同样回登录页。</p>
 */
public final class ConnectionManager implements AutoCloseable {

    /** 最大重试次数。 */
    private static final int MAX_ATTEMPTS = 10;
    /** 单次退避上限。 */
    private static final long MAX_DELAY_MS = 30_000;
    /** 单次连接超时。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 监听器：状态文案与重连结果。 */
    public interface Listener {
        void onStatus(String text);

        void onReconnected();

        void onLoginRequired(String reason);
    }

    private final ImConnection connection;
    private final AuthService auth;
    private final String host;
    private final int port;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();

    private volatile boolean autoReconnect;
    private volatile boolean closed;
    private volatile boolean scheduled;
    private volatile int attempt;
    private volatile String token;

    public ConnectionManager(ImConnection connection, AuthService auth, String host, int port) {
        this.connection = connection;
        this.auth = auth;
        this.host = host;
        this.port = port;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "im-reconnect");
            t.setDaemon(true);
            return t;
        });
        connection.onState(state -> {
            if (state == ImConnection.State.DISCONNECTED) {
                onDisconnected();
            }
        });
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /** 登录成功后启用自动重连。 */
    public void enableAutoReconnect(String token) {
        this.token = token;
        this.autoReconnect = true;
        this.attempt = 0;
    }

    public void disableAutoReconnect() {
        this.autoReconnect = false;
    }

    public ImConnection connection() {
        return connection;
    }

    public AuthService auth() {
        return auth;
    }

    // ---------- 内部 ----------

    private void onDisconnected() {
        if (!autoReconnect || closed) {
            return;
        }
        synchronized (lock) {
            if (scheduled) {
                return;
            }
            if (attempt >= MAX_ATTEMPTS) {
                listeners.forEach(l -> l.onLoginRequired("无法连接服务器"));
                return;
            }
            scheduled = true;
            attempt++;
            long delay = Math.min(1000L << Math.min(attempt - 1, 5), MAX_DELAY_MS);
            listeners.forEach(l -> l.onStatus("重连中（第 " + attempt + " 次）"));
            scheduler.schedule(this::reconnectOnce, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void reconnectOnce() {
        if (closed || !autoReconnect) {
            return;
        }
        try {
            connection.reconnect(host, port, CONNECT_TIMEOUT);
        } catch (IOException e) {
            synchronized (lock) {
                scheduled = false;
            }
            onDisconnected();
            return;
        }
        auth.relogin(token).whenComplete((ReloginResponse response, Throwable error) -> {
            synchronized (lock) {
                scheduled = false;
            }
            if (error != null) {
                onDisconnected();
                return;
            }
            if (response.code() == 0) {
                attempt = 0;
                listeners.forEach(l -> l.onStatus("已恢复连接"));
                listeners.forEach(Listener::onReconnected);
            } else {
                listeners.forEach(l -> l.onLoginRequired("登录状态已失效，请重新登录"));
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        autoReconnect = false;
        scheduler.shutdownNow();
    }
}
