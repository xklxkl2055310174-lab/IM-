package com.im.client;

import com.im.client.net.ImConnection;
import com.im.client.service.AuthService;
import com.im.client.service.ChatService;
import com.im.client.service.ChatService.PendingMessage;
import com.im.client.service.ChatService.Status;
import com.im.client.support.FakeServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超时行为：请求超时（RequestManager）与 ACK 超时（ChatService）。
 *
 * <p>使用只吞字节、不回复的假服务端，验证客户端能正确失败而不是永久阻塞。</p>
 */
class TimeoutTest {

    @Test
    @DisplayName("请求超时：10s 默认超时可由 RequestManager 缩短，登录 future 以 TimeoutException 失败")
    void requestTimesOut() throws Exception {
        try (FakeServer server = FakeServer.start()) {
            ImConnection connection = new ImConnection(Duration.ofMillis(400), Duration.ofSeconds(30), 3);
            connection.connect("127.0.0.1", server.port(), Duration.ofSeconds(3));
            try {
                AuthService auth = new AuthService(connection);
                long start = System.currentTimeMillis();
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> auth.login("nobody", "pwd").get(5, java.util.concurrent.TimeUnit.SECONDS));
                long elapsed = System.currentTimeMillis() - start;
                assertTrue(elapsed < 3000, "应在超时时间内返回，实际 " + elapsed + "ms");
                Throwable cause = error.getCause();
                while (cause != null && !(cause instanceof TimeoutException)) {
                    cause = cause.getCause();
                }
                assertInstanceOf(TimeoutException.class, cause, "根因应为 TimeoutException");
            } finally {
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("ACK 超时：消息状态变为 FAILED")
    void ackTimeoutMarksFailed() throws Exception {
        try (FakeServer server = FakeServer.start()) {
            ImConnection connection = new ImConnection(Duration.ofMillis(400), Duration.ofSeconds(30), 3);
            connection.connect("127.0.0.1", server.port(), Duration.ofSeconds(3));
            ChatService chat = new ChatService(connection, 1001L, Duration.ofMillis(300));
            try {
                PendingMessage message = chat.newMessage(1002L, "hi");
                chat.send(message);

                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (System.nanoTime() < deadline && message.status() == Status.SENDING) {
                    Thread.sleep(30);
                }
                assertEquals(Status.FAILED, message.status());
            } finally {
                chat.close();
                connection.close();
            }
        }
    }

    @Test
    @DisplayName("连接关闭时等待中的请求失败而不是悬挂")
    void closeFailsPendingRequests() throws Exception {
        try (FakeServer server = FakeServer.start()) {
            ImConnection connection = new ImConnection(Duration.ofSeconds(10), Duration.ofSeconds(30), 3);
            connection.connect("127.0.0.1", server.port(), Duration.ofSeconds(3));
            AuthService auth = new AuthService(connection);
            var future = auth.login("nobody", "pwd");
            connection.close();
            assertThrows(ExecutionException.class,
                    () -> future.get(3, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
