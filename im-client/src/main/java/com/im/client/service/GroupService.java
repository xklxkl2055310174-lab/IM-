package com.im.client.service;

import com.im.client.net.ImConnection;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.GroupMessages.GroupCreateRequest;
import com.im.common.message.GroupMessages.GroupCreateResponse;
import com.im.common.message.GroupMessages.GroupJoinRequest;
import com.im.common.message.GroupMessages.GroupJoinResponse;
import com.im.common.message.GroupMessages.GroupListRequest;
import com.im.common.message.GroupMessages.GroupListResponse;
import com.im.common.message.GroupMessages.GroupMembersRequest;
import com.im.common.message.GroupMessages.GroupMembersResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 群组服务：建群、加群、群列表、群成员。
 */
public final class GroupService {

    private final ImConnection connection;

    public GroupService(ImConnection connection) {
        this.connection = connection;
    }

    public CompletableFuture<GroupCreateResponse> create(String name) {
        Packet request = Packet.ofJson(MessageType.GROUP_CREATE_REQ,
                JsonUtil.toJson(new GroupCreateRequest(UUID.randomUUID().toString(), name)));
        return connection.request(request).thenApply(packet -> parse(packet, GroupCreateResponse.class));
    }

    public CompletableFuture<GroupJoinResponse> join(long groupId) {
        Packet request = Packet.ofJson(MessageType.GROUP_JOIN_REQ,
                JsonUtil.toJson(new GroupJoinRequest(UUID.randomUUID().toString(), groupId)));
        return connection.request(request).thenApply(packet -> parse(packet, GroupJoinResponse.class));
    }

    public CompletableFuture<GroupListResponse> list() {
        Packet request = Packet.ofJson(MessageType.GROUP_LIST_REQ,
                JsonUtil.toJson(new GroupListRequest(UUID.randomUUID().toString())));
        return connection.request(request).thenApply(packet -> parse(packet, GroupListResponse.class));
    }

    public CompletableFuture<GroupMembersResponse> members(long groupId) {
        Packet request = Packet.ofJson(MessageType.GROUP_MEMBERS_REQ,
                JsonUtil.toJson(new GroupMembersRequest(UUID.randomUUID().toString(), groupId)));
        return connection.request(request).thenApply(packet -> parse(packet, GroupMembersResponse.class));
    }

    private static <T> T parse(Packet packet, Class<T> type) {
        try {
            return JsonUtil.fromJson(packet.payload(), type);
        } catch (Exception e) {
            throw new IllegalStateException("响应解析失败: " + type.getSimpleName(), e);
        }
    }
}
