package com.im.client.service;

import com.im.client.net.ImConnection;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.ChatMessages.HistoryRequest;
import com.im.common.message.ChatMessages.HistoryResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 历史消息服务：按会话分页拉取（游标 {@code beforeId}，首页传 0）。
 */
public final class HistoryService {

    private final ImConnection connection;

    public HistoryService(ImConnection connection) {
        this.connection = connection;
    }

    /** @param peerType 1=私聊，2=群聊 */
    public CompletableFuture<HistoryResponse> history(long peerId, int peerType, long beforeId, int limit) {
        Packet request = Packet.ofJson(MessageType.HISTORY_REQ, JsonUtil.toJson(
                new HistoryRequest(UUID.randomUUID().toString(), peerId, peerType, beforeId, limit)));
        return connection.request(request).thenApply(packet -> {
            try {
                return JsonUtil.fromJson(packet.payload(), HistoryResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("历史响应解析失败", e);
            }
        });
    }
}
