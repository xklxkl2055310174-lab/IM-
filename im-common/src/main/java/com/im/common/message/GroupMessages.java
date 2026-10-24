package com.im.common.message;

import java.util.List;

/**
 * 群组域 DTO：建群、加群、群列表、群成员。
 */
public final class GroupMessages {

    private GroupMessages() {
    }

    /**
     * 建群请求（{@code GROUP_CREATE_REQ}）。
     *
     * @param requestId 请求标识
     * @param name      群名称
     */
    public record GroupCreateRequest(String requestId, String name) {
    }

    /**
     * 建群响应（{@code GROUP_CREATE_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码
     * @param message   描述
     * @param groupId   新群 id（失败为 0）
     * @param name      群名称
     */
    public record GroupCreateResponse(String requestId, int code, String message, long groupId, String name) {
    }

    /**
     * 加群请求（{@code GROUP_JOIN_REQ}）：凭群号加入。
     *
     * @param requestId 请求标识
     * @param groupId   群 id
     */
    public record GroupJoinRequest(String requestId, long groupId) {
    }

    /**
     * 加群响应（{@code GROUP_JOIN_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码，0 成功；2005 群不存在
     * @param message   描述
     * @param groupId   群 id
     */
    public record GroupJoinResponse(String requestId, int code, String message, long groupId) {
    }

    /**
     * 群列表请求（{@code GROUP_LIST_REQ}）。
     *
     * @param requestId 请求标识
     */
    public record GroupListRequest(String requestId) {
    }

    /**
     * 群列表响应（{@code GROUP_LIST_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码
     * @param message   描述
     * @param groups    我加入的群
     */
    public record GroupListResponse(String requestId, int code, String message, List<GroupItem> groups) {
    }

    /**
     * 群条目。
     *
     * @param groupId     群 id
     * @param name        群名称
     * @param memberCount 成员数
     */
    public record GroupItem(long groupId, String name, int memberCount) {
    }

    /**
     * 群成员请求（{@code GROUP_MEMBERS_REQ}）。
     *
     * @param requestId 请求标识
     * @param groupId   群 id
     */
    public record GroupMembersRequest(String requestId, long groupId) {
    }

    /**
     * 群成员响应（{@code GROUP_MEMBERS_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码，0 成功；2002 非群成员、2005 群不存在
     * @param message   描述
     * @param members   群成员（含在线状态）
     */
    public record GroupMembersResponse(String requestId, int code, String message, List<GroupMember> members) {
    }

    /**
     * 群成员条目。
     *
     * @param userId   用户 id
     * @param username 用户名
     * @param online   是否在线
     */
    public record GroupMember(long userId, String username, boolean online) {
    }
}
