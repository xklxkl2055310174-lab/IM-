package com.im.client.service;

import com.im.client.net.ImConnection;
import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.AccountMessages.LoginRequest;
import com.im.common.message.AccountMessages.LoginResponse;
import com.im.common.message.AccountMessages.LogoutRequest;
import com.im.common.message.AccountMessages.LogoutResponse;
import com.im.common.message.AccountMessages.RegisterRequest;
import com.im.common.message.AccountMessages.RegisterResponse;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 账号服务：注册、登录、退出。对 UI 只暴露异步 API。
 */
public final class AuthService {

    /** 当前会话信息。 */
    public record Session(long userId, String username, String token) {
    }

    private final ImConnection connection;
    private volatile Session session;

    public AuthService(ImConnection connection) {
        this.connection = connection;
    }

    /** 注册。 */
    public CompletableFuture<RegisterResponse> register(String username, String password) {
        String requestId = UUID.randomUUID().toString();
        Packet request = Packet.ofJson(MessageType.REGISTER_REQ,
                JsonUtil.toJson(new RegisterRequest(requestId, username, password)));
        return connection.request(request).thenApply(packet -> {
            try {
                return JsonUtil.fromJson(packet.payload(), RegisterResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("注册响应解析失败", e);
            }
        });
    }

    /** 登录；成功时记录会话并切换连接状态为 AUTHENTICATED。 */
    public CompletableFuture<LoginResponse> login(String username, String password) {
        String requestId = UUID.randomUUID().toString();
        Packet request = Packet.ofJson(MessageType.LOGIN_REQ,
                JsonUtil.toJson(new LoginRequest(requestId, username, password)));
        return connection.request(request).thenApply(packet -> {
            LoginResponse response;
            try {
                response = JsonUtil.fromJson(packet.payload(), LoginResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("登录响应解析失败", e);
            }
            if (response.code() == ErrorCode.OK.code()) {
                this.session = new Session(response.userId(), response.username(), response.token());
                connection.markAuthenticated();
            }
            return response;
        });
    }

    /** 用 token 重登（断线重连后调用）。 */
    public CompletableFuture<com.im.common.message.AccountMessages.ReloginResponse> relogin(String token) {
        String requestId = UUID.randomUUID().toString();
        Packet request = Packet.ofJson(MessageType.RELOGIN_REQ,
                JsonUtil.toJson(new com.im.common.message.AccountMessages.ReloginRequest(requestId, token)));
        return connection.request(request).thenApply(packet -> {
            try {
                var response = JsonUtil.fromJson(packet.payload(),
                        com.im.common.message.AccountMessages.ReloginResponse.class);
                if (response.code() == ErrorCode.OK.code()) {
                    this.session = new Session(response.userId(), response.username(), token);
                    connection.markAuthenticated();
                }
                return response;
            } catch (Exception e) {
                throw new IllegalStateException("重登响应解析失败", e);
            }
        });
    }

    /** 退出登录。 */
    public CompletableFuture<LogoutResponse> logout() {        String requestId = UUID.randomUUID().toString();
        Packet request = Packet.ofJson(MessageType.LOGOUT_REQ,
                JsonUtil.toJson(new LogoutRequest(requestId)));
        return connection.request(request).thenApply(packet -> {
            try {
                return JsonUtil.fromJson(packet.payload(), LogoutResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("退出响应解析失败", e);
            }
        });
    }

    public Session session() {
        return session;
    }

    /** 底层连接（UI 用来监听状态/被踢通知）。 */
    public ImConnection connection() {
        return connection;
    }
}
