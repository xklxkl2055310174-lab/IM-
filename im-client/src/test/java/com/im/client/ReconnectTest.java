package com.im.client;

import com.im.client.net.ImConnection;
import com.im.client.service.AuthService;
import com.im.client.service.ChatService;
import com.im.client.service.ChatService.PendingMessage;
import com.im.client.service.ChatService.Status;
import com.im.client.service.ConnectionManager;
import com.im.client.support.ServerFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断线重连：断开后指数退避重连 + token 自动重登；断线期间发送的消息标记为失败。
 */
class ReconnectTest {

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("断线后自动重连并重登，恢复后可继续收发；断线期间消息标记失败")
    void reconnectAndRelogin() throws Exception {
        int port = freePort();
        try (ServerFixture fixture = ServerFixture.start(port, 0)) {
            ImConnection connection = new ImConnection(Duration.ofSeconds(5), Duration.ofSeconds(30), 3);
            connection.connect("127.0.0.1", port, Duration.ofSeconds(3));

            AuthService auth = new AuthService(connection);
            String name = "re_" + System.nanoTime();
            assertEquals(0, auth.register(name, "pwd123").get(5, TimeUnit.SECONDS).code());
            assertEquals(0, auth.login(name, "pwd123").get(5, TimeUnit.SECONDS).code());

            ChatService chat = new ChatService(connection, auth.session().userId(), Duration.ofSeconds(3));
            ConnectionManager manager = new ConnectionManager(connection, auth, "127.0.0.1", port);
            CountDownLatch reconnected = new CountDownLatch(1);
            AtomicReference<String> lastStatus = new AtomicReference<>("");
            manager.addListener(new ConnectionManager.Listener() {
                @Override
                public void onStatus(String text) {
                    lastStatus.set(text);
                }

                @Override
                public void onReconnected() {
                    reconnected.countDown();
                }

                @Override
                public void onLoginRequired(String reason) {
                    // 不应发生
                }
            });
            manager.enableAutoReconnect(auth.session().token());

            PendingMessage before = chat.sendPrivate(auth.session().userId(), "before").get(5, TimeUnit.SECONDS);
            assertEquals(Status.SENT, before.status());

            // 模拟网络中断
            connection.forceDisconnect();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline && connection.state() != ImConnection.State.DISCONNECTED) {
                Thread.sleep(20);
            }
            assertEquals(ImConnection.State.DISCONNECTED, connection.state());

            // 断线期间发送 → 立即失败
            PendingMessage during = chat.sendPrivate(auth.session().userId(), "during").get(2, TimeUnit.SECONDS);
            assertEquals(Status.FAILED, during.status());

            assertTrue(reconnected.await(15, TimeUnit.SECONDS), "应自动重连并重登，状态: " + lastStatus.get());
            assertEquals(ImConnection.State.AUTHENTICATED, connection.state());

            PendingMessage after = chat.sendPrivate(auth.session().userId(), "after").get(5, TimeUnit.SECONDS);
            assertEquals(Status.SENT, after.status());

            chat.close();
            manager.close();
            connection.close();
        }
    }
}
