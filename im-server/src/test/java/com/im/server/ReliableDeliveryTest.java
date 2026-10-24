package com.im.server;

import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.ChatMessages.MsgAck;
import com.im.common.message.ChatMessages.OfflineMsgItem;
import com.im.common.message.ChatMessages.OfflineMsgPush;
import com.im.common.message.ChatMessages.PrivateMsg;
import com.im.server.support.ServerFixture;
import com.im.server.support.TestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 可靠投递测试：幂等、离线顺序、确认清空、断线重投。
 */
class ReliableDeliveryTest {

    private static ServerFixture fixture;
    private static int seq;

    @BeforeAll
    static void up() throws Exception {
        fixture = ServerFixture.start();
    }

    @AfterAll
    static void down() {
        fixture.close();
    }

    private TestClient newUser() throws Exception {
        String name = "u" + (++seq) + "_" + System.nanoTime();
        TestClient client = new TestClient(fixture.port());
        client.register(name, "pwd");
        assertEquals(0, client.login(name, "pwd").code());
        return client;
    }

    @Test
    @DisplayName("同一 msgId 重发不重复入库")
    void duplicateMsgIdIsIdempotent() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        String msgId = UUID.randomUUID().toString();

        MsgAck first = a.sendPrivate(b.userId, "hello", msgId);
        MsgAck second = a.sendPrivate(b.userId, "hello", msgId);

        assertEquals(0, first.code());
        assertEquals(0, second.code());
        assertEquals(1, fixture.runtime().server().messages().countByMsgId(msgId));
        // 只推送一次
        assertNotNull(b.awaitPush(MessageType.PRIVATE_MSG, Duration.ofSeconds(2)));
        assertEquals(true, b.noPush(MessageType.PRIVATE_MSG, Duration.ofMillis(300)));
        a.close();
        b.close();
    }

    @Test
    @DisplayName("离线 3 条按序推送，确认后收件箱清空")
    void offlineMessagesOrderedAndCleared() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        b.close(); // B 离线

        a.sendPrivate(b.userId, "m1", UUID.randomUUID().toString());
        a.sendPrivate(b.userId, "m2", UUID.randomUUID().toString());
        a.sendPrivate(b.userId, "m3", UUID.randomUUID().toString());

        TestClient b2 = new TestClient(fixture.port());
        assertEquals(0, b2.login(b.username, "pwd").code());

        Packet pushPacket = b2.awaitPush(MessageType.OFFLINE_MSG_PUSH, Duration.ofSeconds(3));
        assertNotNull(pushPacket);
        OfflineMsgPush push = JsonUtil.fromJson(pushPacket.payload(), OfflineMsgPush.class);
        List<OfflineMsgItem> items = push.messages();
        assertEquals(3, items.size());
        assertEquals("m1", items.get(0).content());
        assertEquals("m2", items.get(1).content());
        assertEquals("m3", items.get(2).content());

        items.forEach(item -> {
            try {
                b2.sendDeliverAck(item.msgId());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        awaitInbox(b2.userId, 0, Duration.ofSeconds(3));
        a.close();
        b2.close();
    }

    @Test
    @DisplayName("未确认就断线，重登后再次收到（至少一次投递）")
    void redeliveredWhenNotAcked() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        b.close();

        a.sendPrivate(b.userId, "x1", UUID.randomUUID().toString());
        a.sendPrivate(b.userId, "x2", UUID.randomUUID().toString());

        TestClient first = new TestClient(fixture.port());
        assertEquals(0, first.login(b.username, "pwd").code());
        Packet push1 = first.awaitPush(MessageType.OFFLINE_MSG_PUSH, Duration.ofSeconds(3));
        assertNotNull(push1);
        assertEquals(2, JsonUtil.fromJson(push1.payload(), OfflineMsgPush.class).total());
        first.close(); // 未确认直接断开

        TestClient second = new TestClient(fixture.port());
        assertEquals(0, second.login(b.username, "pwd").code());
        Packet push2 = second.awaitPush(MessageType.OFFLINE_MSG_PUSH, Duration.ofSeconds(3));
        assertNotNull(push2);
        assertEquals(2, JsonUtil.fromJson(push2.payload(), OfflineMsgPush.class).total());
        second.close();
        a.close();
    }

    private void awaitInbox(long userId, int expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (fixture.runtime().server().messages().countInbox(userId) == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(expected, fixture.runtime().server().messages().countInbox(userId));
    }

    @Test
    @DisplayName("私聊发给自己也能收到推送与 ACK")
    void selfPrivateMessage() throws Exception {
        TestClient a = newUser();
        MsgAck ack = a.sendPrivate(a.userId, "to me", UUID.randomUUID().toString());
        assertEquals(0, ack.code());
        assertNotNull(a.awaitPush(MessageType.PRIVATE_MSG, Duration.ofSeconds(2)));
        a.close();
    }

    @Test
    @DisplayName("推送内容与私聊 DTO 字段一致")
    void pushPayloadShape() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        String msgId = UUID.randomUUID().toString();
        a.sendPrivate(b.userId, "shape", msgId);
        Packet packet = b.awaitPush(MessageType.PRIVATE_MSG, Duration.ofSeconds(2));
        assertNotNull(packet);
        PrivateMsg msg = JsonUtil.fromJson(packet.payload(), PrivateMsg.class);
        assertEquals(msgId, msg.msgId());
        assertEquals(a.userId, msg.fromUserId());
        assertEquals(b.userId, msg.toUserId());
        assertEquals("shape", msg.content());
        a.close();
        b.close();
    }
}
