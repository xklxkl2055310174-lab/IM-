package com.im.client.service;

import com.im.client.net.ImConnection;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.ContactMessages.AddFriendRequest;
import com.im.common.message.ContactMessages.AddFriendResponse;
import com.im.common.message.ContactMessages.FriendListRequest;
import com.im.common.message.ContactMessages.FriendListResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 好友服务：添加好友、好友列表（带在线状态）。
 */
public final class FriendService {

    private final ImConnection connection;

    public FriendService(ImConnection connection) {
        this.connection = connection;
    }

    public CompletableFuture<AddFriendResponse> addFriend(String username) {
        Packet request = Packet.ofJson(MessageType.ADD_FRIEND_REQ,
                JsonUtil.toJson(new AddFriendRequest(UUID.randomUUID().toString(), username)));
        return connection.request(request).thenApply(packet -> parse(packet, AddFriendResponse.class));
    }

    public CompletableFuture<FriendListResponse> friends() {
        Packet request = Packet.ofJson(MessageType.FRIEND_LIST_REQ,
                JsonUtil.toJson(new FriendListRequest(UUID.randomUUID().toString())));
        return connection.request(request).thenApply(packet -> parse(packet, FriendListResponse.class));
    }

    private static <T> T parse(Packet packet, Class<T> type) {
        try {
            return JsonUtil.fromJson(packet.payload(), type);
        } catch (Exception e) {
            throw new IllegalStateException("响应解析失败: " + type.getSimpleName(), e);
        }
    }
}
