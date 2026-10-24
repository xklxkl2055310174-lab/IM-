package com.im.bench;

import com.im.client.net.ImConnection;
import com.im.client.service.AuthService;
import com.im.client.service.ChatService;
import com.im.client.service.ChatService.PendingMessage;
import com.im.client.service.ChatService.Status;
import com.im.client.service.FileService;
import com.im.client.service.FriendService;
import com.im.client.service.GroupService;
import com.im.bench.support.ServerFixture;
import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.json.JsonUtil;
import com.im.common.message.ChatMessages.GroupMsg;
import com.im.common.message.ChatMessages.OfflineMsgPush;
import com.im.common.message.ChatMessages.PrivateMsg;
import com.im.common.message.ContactMessages.FriendListResponse;
import com.im.common.message.GroupMessages.GroupMembersResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端测试：注册 → 加好友 → 私聊 → 离线 → 上线收离线 → 群聊 → 文件 MD5 → 断线重连不丢不重。
 */
class E2eTest {

    private static ServerFixture fixture;
    private static ScheduledExecutorService heartbeat;

    @BeforeAll
    static void up() throws Exception {
        fixture = ServerFixture.start();
        heartbeat = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "e2e-hb");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterAll
    static void down() {
        heartbeat.shutdownNow();
        fixture.close();
    }

    /** 测试客户端聚合。 */
    private record Client(ImConnection connection, AuthService auth, ChatService chat, long userId,
                          String username) implements AutoCloseable {
        static Client register(String username) throws Exception {
            ImConnection connection = new ImConnection(Duration.ofSeconds(10), Duration.ofSeconds(30), 3,
                    heartbeat, false);
            connection.connect("127.0.0.1", fixture.port(), Duration.ofSeconds(5));
            AuthService auth = new AuthService(connection);
            assertEquals(0, auth.register(username, "pwd123").get(10, TimeUnit.SECONDS).code());
            var login = auth.login(username, "pwd123").get(10, TimeUnit.SECONDS);
            assertEquals(0, login.code());
            return new Client(connection, auth, new ChatService(connection, login.userId()),
                    login.userId(), username);
        }

        static Client loginOnly(String username) throws Exception {
            ImConnection connection = new ImConnection(Duration.ofSeconds(10), Duration.ofSeconds(30), 3,
                    heartbeat, false);
            connection.connect("127.0.0.1", fixture.port(), Duration.ofSeconds(5));
            AuthService auth = new AuthService(connection);
            var login = auth.login(username, "pwd123").get(10, TimeUnit.SECONDS);
            assertEquals(0, login.code());
            return new Client(connection, auth, new ChatService(connection, login.userId()),
                    login.userId(), username);
        }

        @Override
        public void close() {
            chat.close();
            connection.close();
        }
    }

    @Test
    @DisplayName("注册→好友→私聊→离线→上线收离线→群聊→文件→重连不丢不重")
    void fullFlow() throws Exception {
        String tag = Long.toString(System.nanoTime());
        try (Client a = Client.register("a_" + tag); Client b = Client.register("b_" + tag)) {

            // 好友
            FriendService friendService = new FriendService(a.connection());
            assertEquals(0, friendService.addFriend(b.username()).get(10, TimeUnit.SECONDS).code());
            FriendListResponse friends = friendService.friends().get(10, TimeUnit.SECONDS);
            assertTrue(friends.friends().stream().anyMatch(f -> f.userId() == b.userId()));

            // 私聊
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<PrivateMsg> incoming = new AtomicReference<>();
            b.chat().onIncoming(msg -> {
                incoming.set(msg);
                received.countDown();
            });
            PendingMessage sent = a.chat().sendPrivate(b.userId(), "hello").get(10, TimeUnit.SECONDS);
            assertEquals(Status.SENT, sent.status());
            assertTrue(received.await(10, TimeUnit.SECONDS));
            assertEquals("hello", incoming.get().content());

            // 对方离线后发消息
            b.connection().close();
            String offlineMsgId = UUID.randomUUID().toString();
            assertEquals(Status.SENT, a.chat().sendPrivate(b.userId(), "offline", offlineMsgId)
                    .get(10, TimeUnit.SECONDS).status());

            // 上线重登，服务端推送离线消息（至少一次：未确认的 "hello" 也会重推）
            List<String> offlineContents = new java.util.concurrent.CopyOnWriteArrayList<>();
            CountDownLatch offlineLatch = new CountDownLatch(1);
            try (Client b2 = Client.loginOnly(b.username())) {
                b2.connection().on(MessageType.OFFLINE_MSG_PUSH, packet -> {
                    try {
                        OfflineMsgPush push = JsonUtil.fromJson(packet.payload(), OfflineMsgPush.class);
                        for (var item : push.messages()) {
                            offlineContents.add(item.content());
                        }
                        if (offlineContents.contains("offline")) {
                            offlineLatch.countDown();
                        }
                    } catch (Exception ignored) {
                        // 忽略
                    }
                });
                b2.auth().relogin(b2.auth().session().token()).get(10, TimeUnit.SECONDS);
                assertTrue(offlineLatch.await(10, TimeUnit.SECONDS), "应收到离线消息");
                assertTrue(offlineContents.contains("offline"), "离线队列应包含 offline，实际: " + offlineContents);

                // 群聊
                GroupService groups = new GroupService(a.connection());
                long groupId = groups.create("e2e").get(10, TimeUnit.SECONDS).groupId();
                assertTrue(groupId > 0);
                assertEquals(0, new GroupService(b2.connection()).join(groupId)
                        .get(10, TimeUnit.SECONDS).code());
                GroupMembersResponse members = groups.members(groupId).get(10, TimeUnit.SECONDS);
                assertEquals(2, members.members().size());

                CountDownLatch groupLatch = new CountDownLatch(1);
                b2.connection().on(MessageType.GROUP_MSG, packet -> {
                    try {
                        GroupMsg msg = JsonUtil.fromJson(packet.payload(), GroupMsg.class);
                        if (msg.groupId() == groupId) {
                            groupLatch.countDown();
                        }
                    } catch (Exception ignored) {
                        // 忽略
                    }
                });
                a.chat().sendGroup(groupId, "hi group").get(10, TimeUnit.SECONDS);
                assertTrue(groupLatch.await(10, TimeUnit.SECONDS), "群成员应收到群消息");

                // 文件上传下载 MD5
                byte[] data = ("file-" + tag).getBytes();
                Path source = Files.createTempFile("e2e", ".bin");
                Files.write(source, data);
                FileService aFiles = new FileService("127.0.0.1", fixture.filePort(),
                        a.auth().session().token());
                String fileId = aFiles.upload(source, b2.userId(), null, new AtomicBoolean(false))
                        .get(15, TimeUnit.SECONDS);
                Path target = Files.createTempFile("e2e-out", ".bin");
                FileService bFiles = new FileService("127.0.0.1", fixture.filePort(),
                        b2.auth().session().token());
                bFiles.download(fileId, target, md5(data), null, new AtomicBoolean(false))
                        .get(15, TimeUnit.SECONDS);
                assertArrayEquals(data, Files.readAllBytes(target));
                assertEquals(md5(data), md5(Files.readAllBytes(target)));
            }

            // 断线重连 + 幂等：同一 msgId 两次只入库一次
            String token = a.auth().session().token();
            a.connection().forceDisconnect();
            Thread.sleep(300);
            a.connection().reconnect("127.0.0.1", fixture.port(), Duration.ofSeconds(5));
            assertEquals(0, a.auth().relogin(token).get(10, TimeUnit.SECONDS).code());

            String reliableId = UUID.randomUUID().toString();
            assertEquals(Status.SENT, a.chat().sendPrivate(b.userId(), "again", reliableId)
                    .get(10, TimeUnit.SECONDS).status());
            assertEquals(Status.SENT, a.chat().sendPrivate(b.userId(), "again", reliableId)
                    .get(10, TimeUnit.SECONDS).status());
            assertEquals(1, fixture.runtime().server().messages().countByMsgId(reliableId));
        }
    }

    private static String md5(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }
}
