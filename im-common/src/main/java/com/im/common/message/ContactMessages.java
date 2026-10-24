package com.im.common.message;

import java.util.List;

/**
 * 联系人域 DTO：好友列表、加好友、在线用户、上下线通知。
 */
public final class ContactMessages {

    private ContactMessages() {
    }

    /**
     * 好友列表请求（{@code FRIEND_LIST_REQ}）。
     *
     * @param requestId 请求标识
     */
    public record FriendListRequest(String requestId) {
    }

    /**
     * 好友列表响应（{@code FRIEND_LIST_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码
     * @param message   描述
     * @param friends   好友列表（含实时在线状态）
     */
    public record FriendListResponse(String requestId, int code, String message, List<FriendItem> friends) {
    }

    /**
     * 好友条目。
     *
     * @param userId   好友 id
     * @param username 好友用户名
     * @param online   是否在线
     */
    public record FriendItem(long userId, String username, boolean online) {
    }

    /**
     * 添加好友请求（{@code ADD_FRIEND_REQ}）：按用户名添加，双向、免审批。
     *
     * @param requestId 请求标识
     * @param username  对方用户名
     */
    public record AddFriendRequest(String requestId, String username) {
    }

    /**
     * 添加好友响应（{@code ADD_FRIEND_RESP}）。
     *
     * @param requestId      对应请求 id
     * @param code           错误码，0 成功；2003 用户不存在
     * @param message        描述
     * @param friendUserId   好友 id
     * @param friendUsername 好友用户名
     */
    public record AddFriendResponse(String requestId, int code, String message,
                                    long friendUserId, String friendUsername) {
    }

    /**
     * 在线用户列表请求（{@code ONLINE_USERS_REQ}）。
     *
     * @param requestId 请求标识
     */
    public record OnlineUsersRequest(String requestId) {
    }

    /**
     * 在线用户列表响应（{@code ONLINE_USERS_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码
     * @param message   描述
     * @param users     在线用户
     */
    public record OnlineUsersResponse(String requestId, int code, String message, List<OnlineUser> users) {
    }

    /**
     * 在线用户条目。
     *
     * @param userId   用户 id
     * @param username 用户名
     */
    public record OnlineUser(long userId, String username) {
    }

    /**
     * 用户上下线通知（{@code USER_STATUS_NOTIFY}）。
     *
     * @param userId   用户 id
     * @param username 用户名
     * @param online   true 上线、false 下线
     */
    public record UserStatusNotify(long userId, String username, boolean online) {
    }
}
