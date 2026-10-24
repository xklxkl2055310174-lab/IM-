package com.im.server;

import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.ChatMessages.GroupMsg;
import com.im.common.message.ChatMessages.MsgAck;
import com.im.common.message.ContactMessages.AddFriendResponse;
import com.im.common.message.ContactMessages.FriendItem;
import com.im.common.message.ContactMessages.FriendListResponse;
import com.im.common.message.GroupMessages.GroupCreateResponse;
import com.im.common.message.GroupMessages.GroupJoinResponse;
import com.im.common.message.GroupMessages.GroupListResponse;
import com.im.common.message.GroupMessages.GroupMember;
import com.im.common.message.GroupMessages.GroupMembersResponse;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 好友与群组测试：双向好友、群成员校验、扇出与离线入箱。
 */
class SocialGroupTest {

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
        String name = "s" + (++seq) + "_" + System.nanoTime();
        TestClient client = new TestClient(fixture.port());
        client.register(name, "pwd");
        assertEquals(0, client.login(name, "pwd").code());
        return client;
    }

    @Test
    @DisplayName("加好友是双向的，好友列表带在线状态")
    void addFriendIsBidirectional() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();

        AddFriendResponse resp = a.addFriend(b.username);
        assertEquals(0, resp.code());
        assertEquals(b.userId, resp.friendUserId());

        FriendListResponse bFriends = b.friends();
        assertTrue(bFriends.friends().stream().anyMatch(f -> f.userId() == a.userId()),
                "B 的好友列表应包含 A");
        FriendItem item = bFriends.friends().stream().filter(f -> f.userId() == a.userId()).findFirst().orElseThrow();
        assertTrue(item.online(), "A 在线");

        FriendListResponse aFriends = a.friends();
        assertTrue(aFriends.friends().stream().anyMatch(f -> f.userId() == b.userId()));
        a.close();
        b.close();
    }

    @Test
    @DisplayName("建群后创建者自动入群，群列表与成员列表正确")
    void createGroupAddsOwner() throws Exception {
        TestClient a = newUser();
        GroupCreateResponse created = a.createGroup("项目组");
        assertEquals(0, created.code());

        GroupListResponse groups = a.groups();
        assertTrue(groups.groups().stream().anyMatch(g -> g.groupId() == created.groupId()));

        GroupMembersResponse members = a.members(created.groupId());
        assertEquals(0, members.code());
        List<GroupMember> list = members.members();
        assertEquals(1, list.size());
        assertEquals(a.userId, list.get(0).userId());
        assertTrue(list.get(0).online());
        a.close();
    }

    @Test
    @DisplayName("非群成员发群消息被拒；成员全部收到（除发送者）")
    void nonMemberRejectedAndMembersReceive() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        TestClient c = newUser();

        long groupId = a.createGroup("g1").groupId();
        assertEquals(0, b.joinGroup(groupId).code());

        // C 不在群里
        MsgAck rejected = c.sendGroup(groupId, "hello", UUID.randomUUID().toString());
        assertEquals(ErrorCode.NO_PERMISSION.code(), rejected.code());

        // A 发群消息，B 收到，A 自己不收
        MsgAck ack = a.sendGroup(groupId, "大家好", UUID.randomUUID().toString());
        assertEquals(0, ack.code());
        Packet packet = b.awaitPush(MessageType.GROUP_MSG, Duration.ofSeconds(2));
        assertNotNull(packet);
        GroupMsg msg = JsonUtil.fromJson(packet.payload(), GroupMsg.class);
        assertEquals(groupId, msg.groupId());
        assertEquals(a.userId, msg.fromUserId());
        assertEquals("大家好", msg.content());
        assertEquals(true, a.noPush(MessageType.GROUP_MSG, Duration.ofMillis(300)));

        a.close();
        b.close();
        c.close();
    }

    @Test
    @DisplayName("离线群成员登录后收到入箱的群消息")
    void offlineGroupMemberGetsMessage() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        long groupId = a.createGroup("g2").groupId();
        b.joinGroup(groupId);
        b.close();

        a.sendGroup(groupId, "离线消息", UUID.randomUUID().toString());

        TestClient b2 = new TestClient(fixture.port());
        assertEquals(0, b2.login(b.username, "pwd").code());
        Packet push = b2.awaitPush(MessageType.OFFLINE_MSG_PUSH, Duration.ofSeconds(3));
        assertNotNull(push);
        assertEquals(1, JsonUtil.fromJson(push.payload(),
                com.im.common.message.ChatMessages.OfflineMsgPush.class).total());
        a.close();
        b2.close();
    }

    @Test
    @DisplayName("非成员查看群成员/群历史被拒")
    void nonMemberCannotInspectGroup() throws Exception {
        TestClient a = newUser();
        TestClient outsider = newUser();
        long groupId = a.createGroup("g3").groupId();

        assertEquals(ErrorCode.NO_PERMISSION.code(), outsider.members(groupId).code());
        assertEquals(ErrorCode.NO_PERMISSION.code(), outsider.history(groupId, 2, 0, 50).code());
        a.close();
        outsider.close();
    }

    @Test
    @DisplayName("重复加群是幂等的")
    void joinTwiceIsIdempotent() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        long groupId = a.createGroup("g4").groupId();
        GroupJoinResponse first = b.joinGroup(groupId);
        GroupJoinResponse second = b.joinGroup(groupId);
        assertEquals(0, first.code());
        assertEquals(0, second.code());
        assertEquals(2, a.members(groupId).members().size());
        a.close();
        b.close();
    }
}
