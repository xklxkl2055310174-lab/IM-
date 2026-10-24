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
import com.im.common.message.AccountMessages.ReloginRequest;
import com.im.common.message.AccountMessages.ReloginResponse;
import com.im.common.message.ChatMessages.MsgAck;
import com.im.common.message.ChatMessages.PrivateMsg;
import com.im.common.message.SystemMessages.Ping;
import com.im.common.message.SystemMessages.Pong;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 业务 API 层：把协议细节封装成"注册 / 登录 / 发消息 / 心跳"等语义方法。
 *
 * <p>UI 与 bench 都只依赖本层，不直接拼帧。</p>
 */
public final class ImService implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final ImConnection connection;
    private final String host;
    private final int port;

    private long userId;
    private String username;
    private String token;

    public ImService(String host, int port) {
        this.host = host;
        this.port = port;
        this.connection = new ImConnection();
    }

    /** 建立 TCP 连接。 */
    public void connect() throws IOException {
        connection.connect(host, port, Duration.ofSeconds(5));
    }

    /** 注册新账号。 */
    public RegisterResponse register(String user, String password) throws IOException, Exception {
        String requestId = UUID.randomUUID().toString();
        Packet response = connection.request(Packet.ofJson(MessageType.REGISTER_REQ,
                JsonUtil.toJson(new RegisterRequest(requestId, user, password))), DEFAULT_TIMEOUT);
        return JsonUtil.fromJson(response.payload(), RegisterResponse.class);
    }

    /** 登录。成功后进入 AUTHENTICATED 状态并保存 token。 */
    public LoginResponse login(String user, String password) throws IOException, Exception {
        String requestId = UUID.randomUUID().toString();
        Packet response = connection.request(Packet.ofJson(MessageType.LOGIN_REQ,
                JsonUtil.toJson(new LoginRequest(requestId, user, password))), DEFAULT_TIMEOUT);
        LoginResponse login = JsonUtil.fromJson(response.payload(), LoginResponse.class);
        if (login.code() == ErrorCode.OK.code()) {
            this.userId = login.userId();
            this.username = login.username();
            this.token = login.token();
            connection.markAuthenticated();
        }
        return login;
    }

    /** 用 token 重登（断线恢复）。 */
    public ReloginResponse relogin(String token) throws IOException, Exception {
        String requestId = UUID.randomUUID().toString();
        Packet response = connection.request(Packet.ofJson(MessageType.RELOGIN_REQ,
                JsonUtil.toJson(new ReloginRequest(requestId, token))), DEFAULT_TIMEOUT);
        ReloginResponse relogin = JsonUtil.fromJson(response.payload(), ReloginResponse.class);
        if (relogin.code() == ErrorCode.OK.code()) {
            this.userId = relogin.userId();
            this.username = relogin.username();
            connection.markAuthenticated();
        }
        return relogin;
    }

    /** 退出登录。 */
    public LogoutResponse logout() throws IOException, Exception {
        String requestId = UUID.randomUUID().toString();
        Packet response = connection.request(Packet.ofJson(MessageType.LOGOUT_REQ,
                JsonUtil.toJson(new LogoutRequest(requestId))), DEFAULT_TIMEOUT);
        return JsonUtil.fromJson(response.payload(), LogoutResponse.class);
    }

    /** 心跳，返回 RTT（毫秒）；失败返回 -1。 */
    public long ping() {
        try {
            String requestId = UUID.randomUUID().toString();
            long start = System.currentTimeMillis();
            Packet response = connection.request(Packet.ofJson(MessageType.PING,
                    JsonUtil.toJson(new Ping(requestId, start))), DEFAULT_TIMEOUT);
            Pong pong = JsonUtil.fromJson(response.payload(), Pong.class);
            return pong.timestamp() <= 0 ? -1 : System.currentTimeMillis() - pong.timestamp();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 发送私聊消息并等待 ACK。 */
    public MsgAck sendPrivate(long toUserId, String content) throws IOException, Exception {
        String requestId = UUID.randomUUID().toString();
        String msgId = UUID.randomUUID().toString();
        Packet response = connection.request(Packet.ofJson(MessageType.PRIVATE_MSG,
                JsonUtil.toJson(new PrivateMsg(requestId, msgId, userId, toUserId, content,
                        System.currentTimeMillis()))), DEFAULT_TIMEOUT);
        return JsonUtil.fromJson(response.payload(), MsgAck.class);
    }

    /** 注册推送监听（消息、状态通知等）。 */
    public void on(MessageType type, Consumer<Packet> listener) {
        connection.on(type, listener);
    }

    public long userId() {
        return userId;
    }

    public String username() {
        return username;
    }

    public String token() {
        return token;
    }

    public ImConnection connection() {
        return connection;
    }

    @Override
    public void close() {
        connection.close();
    }
}
