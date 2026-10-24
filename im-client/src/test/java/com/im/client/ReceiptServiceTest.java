package com.im.client.service;

import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.ChatMessages.DeliverAck;
import com.im.common.message.ChatMessages.GroupMsg;
import com.im.common.message.ChatMessages.OfflineMsgItem;
import com.im.common.message.ChatMessages.OfflineMsgPush;
import com.im.common.message.ChatMessages.PrivateMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可靠接收：去重与批量 DELIVER_ACK（纯单元测试，不需要网络）。
 */
class ReceiptServiceTest {

    private static Packet privateMsg(String msgId) {
        return Packet.ofJson(MessageType.PRIVATE_MSG, JsonUtil.toJson(
                new PrivateMsg("", msgId, 1, 2, "hi", System.currentTimeMillis())));
    }

    private static Packet groupMsg(String msgId) {
        return Packet.ofJson(MessageType.GROUP_MSG, JsonUtil.toJson(
                new GroupMsg("", msgId, 1, 9, "hi group", System.currentTimeMillis())));
    }

    private static Packet offlinePush(String... msgIds) {
        List<OfflineMsgItem> items = new ArrayList<>();
        long id = 1;
        for (String msgId : msgIds) {
            items.add(new OfflineMsgItem(id++, msgId, 1, "offline", System.currentTimeMillis()));
        }
        return Packet.ofJson(MessageType.OFFLINE_MSG_PUSH,
                JsonUtil.toJson(new OfflineMsgPush(items, items.size(), id - 1)));
    }

    @Test
    @DisplayName("同一 msgId 只确认一次；200ms 内多类消息合并成一批")
    void dedupeAndBatch() throws Exception {
        List<Packet> sent = Collections.synchronizedList(new ArrayList<>());
        ReceiptService receipts = new ReceiptService(sent::add, Duration.ofMillis(200));
        try {
            receipts.accept(privateMsg("m-1"));
            receipts.accept(privateMsg("m-1")); // 重复推送
            receipts.accept(groupMsg("g-1"));
            receipts.accept(offlinePush("o-1", "o-2"));

            Thread.sleep(80);
            assertEquals(0, sent.size(), "200ms 前不应发出确认（批量窗口）");

            Thread.sleep(400);
            assertEquals(4, sent.size(), "应确认 4 条唯一消息");
            assertEquals(1, receipts.flushCount(), "应合并为 1 批");
            assertEquals(4, receipts.ackCount());

            List<String> ackedIds = new ArrayList<>();
            for (Packet packet : sent) {
                assertEquals(MessageType.DELIVER_ACK, packet.type());
                ackedIds.add(JsonUtil.fromJson(packet.payload(), DeliverAck.class).msgId());
            }
            assertEquals(List.of("m-1", "g-1", "o-1", "o-2"), ackedIds);
        } finally {
            receipts.close();
        }
    }

    @Test
    @DisplayName("两批消息分别确认，且不重复")
    void twoBatches() throws Exception {
        List<Packet> sent = Collections.synchronizedList(new ArrayList<>());
        ReceiptService receipts = new ReceiptService(sent::add, Duration.ofMillis(120));
        try {
            receipts.accept(privateMsg("a-1"));
            Thread.sleep(250);
            receipts.accept(privateMsg("a-1")); // 已确认过，不再发
            receipts.accept(privateMsg("a-2"));
            Thread.sleep(250);
            assertEquals(2, sent.size());
            assertTrue(receipts.flushCount() >= 2);
        } finally {
            receipts.close();
        }
    }
}
