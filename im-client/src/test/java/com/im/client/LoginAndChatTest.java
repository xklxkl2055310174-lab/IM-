package com.im.client;

import com.im.client.net.ImConnection;
import com.im.client.service.AuthService;
import com.im.client.service.ChatService;
import com.im.client.service.ChatService.PendingMessage;
import com.im.client.service.ChatService.Status;
import com.im.client.support.ServerFixture;
import com.im.common.message.AccountMessages.LoginResponse;
import com.im.common.message.AccountMessages.RegisterResponse;
import com.im.common.message.ChatMessages.PrivateMsg;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录与私聊收发（真实服务端、随机端口）。
 */
class LoginAndChatTest {

    private static ServerFixture fixture;

    @BeforeAll
    static void up() throws Exception {
        fixture = ServerFixture.start();
    }

    @AfterAll
    static void down() {
        fixture.close();
    }

    /** 一个登录好的客户端。 */
    private record Client(ImConnection connection, AuthService auth, ChatService chat) implements AutoCloseable {
        @Override
        public void close() {
            chat.close();
            connection.close();
        }
    }

    private Client newClient(String name) throws Exception {
        ImConnection connection = new ImConnection(Duration.ofSeconds(5), Duration.ofSeconds(30), 3);
        connection.connect("127.0.0.1", fixture.port(), Duration.ofSeconds(3));
        AuthService auth = new AuthService(connection);
        RegisterResponse register = auth.register(name, "pwd123").get(5, TimeUnit.SECONDS);
        assertEquals(0, register.code());
        LoginResponse login = auth.login(name, "pwd123").get(5, TimeUnit.SECONDS);
        assertEquals(0, login.code());
        assertEquals(ImConnection.State.AUTHENTICATED, connection.state());
        assertNotNull(auth.session());
        assertTrue(auth.session().token() != null && !auth.session().token().isBlank());
        return new Client(connection, auth, new ChatService(connection, auth.session().userId()));
    }

    @Test
    @DisplayName("注册 + 登录成功，状态机进入 AUTHENTICATED")
    void loginSucceeds() throws Exception {
        try (Client client = newClient("login_" + System.nanoTime())) {
            assertEquals(ImConnection.State.AUTHENTICATED, client.connection().state());
        }
    }

    @Test
    @DisplayName("密码错误返回 1002 且不进入已认证状态")
    void wrongPasswordFails() throws Exception {
        String name = "wrong_" + System.nanoTime();
        ImConnection connection = new ImConnection(Duration.ofSeconds(5), Duration.ofSeconds(30), 3);
        connection.connect("127.0.0.1", fixture.port(), Duration.ofSeconds(3));
        try (connection) {
            AuthService auth = new AuthService(connection);
            auth.register(name, "pwd123").get(5, TimeUnit.SECONDS);
            LoginResponse login = auth.login(name, "bad-password").get(5, TimeUnit.SECONDS);
            assertEquals(1002, login.code());
            assertEquals(ImConnection.State.CONNECTED, connection.state());
        }
    }

    @Test
    @DisplayName("私聊：A 发送后 B 收到，A 的消息状态变为已发送")
    void privateMessageRoundTrip() throws Exception {
        try (Client a = newClient("a_" + System.nanoTime());
             Client b = newClient("b_" + System.nanoTime())) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<PrivateMsg> incoming = new AtomicReference<>();
            b.chat().onIncoming(msg -> {
                incoming.set(msg);
                received.countDown();
            });

            PendingMessage message = a.chat().sendPrivate(b.auth().session().userId(), "你好，B")
                    .get(5, TimeUnit.SECONDS);

            assertTrue(received.await(5, TimeUnit.SECONDS), "B 应收到消息");
            assertNotNull(incoming.get());
            assertEquals("你好，B", incoming.get().content());
            assertEquals(a.auth().session().userId(), incoming.get().fromUserId());
            assertEquals(Status.SENT, message.status());
        }
    }

    @Test
    @DisplayName("消息 id 在重发时复用，服务端幂等不重复入库")
    void resendReusesMsgId() throws Exception {
        try (Client a = newClient("r_" + System.nanoTime())) {
            long self = a.auth().session().userId();
            PendingMessage message = a.chat().sendPrivate(self, "self").get(5, TimeUnit.SECONDS);
            assertEquals(Status.SENT, message.status());

            a.chat().resend(message).get(5, TimeUnit.SECONDS);
            assertEquals(Status.SENT, message.status());
            assertEquals(1, fixture.runtime().server().messages().countByMsgId(message.msgId()));
        }
    }
}
