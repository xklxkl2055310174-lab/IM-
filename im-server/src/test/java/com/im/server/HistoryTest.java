package com.im.server;

import com.im.common.ErrorCode;
import com.im.common.message.ChatMessages.HistoryResponse;
import com.im.common.message.ChatMessages.OfflineMsgItem;
import com.im.server.support.ServerFixture;
import com.im.server.support.TestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史消息测试：分页游标、越权查询。
 */
class HistoryTest {

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
        String name = "h" + (++seq) + "_" + System.nanoTime();
        TestClient client = new TestClient(fixture.port());
        client.register(name, "pwd");
        assertEquals(0, client.login(name, "pwd").code());
        return client;
    }

    @Test
    @DisplayName("私聊历史按 id 倒序分页")
    void privateHistoryPaging() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        for (int i = 1; i <= 5; i++) {
            a.sendPrivate(b.userId, "m" + i, UUID.randomUUID().toString());
        }

        HistoryResponse page1 = a.history(b.userId, 1, 0, 2);
        assertEquals(0, page1.code());
        List<OfflineMsgItem> first = page1.messages();
        assertEquals(2, first.size());
        assertEquals("m5", first.get(0).content());
        assertEquals("m4", first.get(1).content());
        assertTrue(first.get(0).id() > first.get(1).id());

        long cursor = first.get(1).id();
        HistoryResponse page2 = a.history(b.userId, 1, cursor, 2);
        assertEquals(2, page2.messages().size());
        assertEquals("m3", page2.messages().get(0).content());
        assertEquals("m2", page2.messages().get(1).content());

        long cursor2 = page2.messages().get(1).id();
        HistoryResponse page3 = a.history(b.userId, 1, cursor2, 2);
        assertEquals(1, page3.messages().size());
        assertEquals("m1", page3.messages().get(0).content());
        a.close();
        b.close();
    }

    @Test
    @DisplayName("非对话方查询私聊历史返回空，不算越权泄露")
    void privateHistoryScopedToParticipant() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        TestClient c = newUser();
        a.sendPrivate(b.userId, "secret", UUID.randomUUID().toString());

        HistoryResponse resp = c.history(b.userId, 1, 0, 50);
        assertEquals(0, resp.code());
        assertTrue(resp.messages().isEmpty(), "C 看不到 A 和 B 的私聊");
        a.close();
        b.close();
        c.close();
    }

    @Test
    @DisplayName("群历史：成员可查、非成员被拒")
    void groupHistoryPermission() throws Exception {
        TestClient a = newUser();
        TestClient outsider = newUser();
        long groupId = a.createGroup("hist").groupId();
        a.sendGroup(groupId, "g1", UUID.randomUUID().toString());
        a.sendGroup(groupId, "g2", UUID.randomUUID().toString());

        HistoryResponse ok = a.history(groupId, 2, 0, 50);
        assertEquals(0, ok.code());
        assertEquals(2, ok.messages().size());
        assertEquals("g2", ok.messages().get(0).content());

        HistoryResponse denied = outsider.history(groupId, 2, 0, 50);
        assertEquals(ErrorCode.NO_PERMISSION.code(), denied.code());
        a.close();
        outsider.close();
    }

    @Test
    @DisplayName("默认条数 50、上限 100")
    void historyLimits() throws Exception {
        TestClient a = newUser();
        TestClient b = newUser();
        for (int i = 0; i < 60; i++) {
            a.sendPrivate(b.userId, "n" + i, UUID.randomUUID().toString());
        }
        assertEquals(50, a.history(b.userId, 1, 0, 0).messages().size());
        assertEquals(60, a.history(b.userId, 1, 0, 1000).messages().size());
        a.close();
        b.close();
    }
}
