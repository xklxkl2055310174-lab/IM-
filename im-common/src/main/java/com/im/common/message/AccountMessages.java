package com.im.common.message;

/**
 * 账号域消息 DTO：注册、登录、重登、退出。
 *
 * <p>请求类消息均携带 {@code requestId}（客户端生成，建议 UUID），响应原样带回，
 * 客户端据此把"请求—响应"配对。每个 record 都同时是 JSON 负载结构，
 * 字段名与 {@code docs/protocol.md} 一一对应。</p>
 */
public final class AccountMessages {

    private AccountMessages() {
    }

    /**
     * 注册请求（{@code REGISTER_REQ}）。
     *
     * @param requestId 请求标识，响应原样带回
     * @param username  用户名（唯一）
     * @param password  明文密码（服务端以 PBKDF2 加盐哈希后落库）
     */
    public record RegisterRequest(String requestId, String username, String password) {
    }

    /**
     * 注册响应（{@code REGISTER_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码，0 成功；1001 用户名已存在
     * @param message   描述
     * @param userId    新用户 id，失败为 0
     */
    public record RegisterResponse(String requestId, int code, String message, long userId) {
    }

    /**
     * 登录请求（{@code LOGIN_REQ}）。
     *
     * @param requestId 请求标识
     * @param username  用户名
     * @param password  密码
     */
    public record LoginRequest(String requestId, String username, String password) {
    }

    /**
     * 登录响应（{@code LOGIN_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码，0 成功；1002 密码错误
     * @param message   描述
     * @param userId    用户 id
     * @param username  回显用户名
     * @param token     会话令牌，用于重登与断线恢复
     */
    public record LoginResponse(String requestId, int code, String message,
                                long userId, String username, String token) {
    }

    /**
     * token 重登请求（{@code RELOGIN_REQ}）。
     *
     * @param requestId 请求标识
     * @param token     登录时下发的 token
     */
    public record ReloginRequest(String requestId, String token) {
    }

    /**
     * token 重登响应（{@code RELOGIN_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码，0 成功；2001 token 无效/过期
     * @param message   描述
     * @param userId    用户 id
     * @param username  用户名
     */
    public record ReloginResponse(String requestId, int code, String message,
                                  long userId, String username) {
    }

    /**
     * 退出请求（{@code LOGOUT_REQ}）。
     *
     * @param requestId 请求标识
     */
    public record LogoutRequest(String requestId) {
    }

    /**
     * 退出响应（{@code LOGOUT_RESP}）。
     *
     * @param requestId 对应请求 id
     * @param code      错误码，0 成功
     * @param message   描述
     */
    public record LogoutResponse(String requestId, int code, String message) {
    }
}
