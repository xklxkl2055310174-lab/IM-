package com.im.client.service;

import com.im.client.net.ImConnection;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.ContactMessages.OnlineUsersRequest;
import com.im.common.message.ContactMessages.OnlineUsersResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 联系人服务：目前提供在线用户列表。
 */
public final class ContactService {

    private final ImConnection connection;

    public ContactService(ImConnection connection) {
        this.connection = connection;
    }

    /** 拉取在线用户。 */
    public CompletableFuture<OnlineUsersResponse> onlineUsers() {
        String requestId = UUID.randomUUID().toString();
        Packet request = Packet.ofJson(MessageType.ONLINE_USERS_REQ,
                JsonUtil.toJson(new OnlineUsersRequest(requestId)));
        return connection.request(request).thenApply(packet -> {
            try {
                return JsonUtil.fromJson(packet.payload(), OnlineUsersResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("在线用户响应解析失败", e);
            }
        });
    }
}
