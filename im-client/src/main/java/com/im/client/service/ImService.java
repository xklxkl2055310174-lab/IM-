package com.im.client.service;

import com.im.client.net.ImConnection;
import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.AccountMessages.LoginRequest;
import com.im.common.message.AccountMessages.LoginResponse;
import com.im.common.message.AccountMessages.RegisterRequest;
import com.im.common.message.AccountMessages.RegisterResponse;
import com.im.common.message.ChatMessages.MsgAck;
import com.im.common.message.ChatMessages.PrivateMsg;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 同步门面：供 im-bench 复用（bench 需要"发一条等一条 ACK"的阻塞语义）。
 *
 * <p>新代码请直接使用 {@link AuthService}/{@link ChatService}/{@link ContactService} 的异步 API。</p>
 */
public final class ImService implements AutoCloseable {

    private final ImConnection connection;
    private final String host;
    private final int port;
    private long userId;

    public ImService(String host, int port) {
        this.host = host;
        this.port = port;
        this.connection = new ImConnection(Duration.ofSeconds(10), Duration.ofSeconds(30), 3);
    }

    public void connect() throws IOException {
        connection.connect(host, port, Duration.ofSeconds(5));
    }

    public LoginResponse login(String username, String password) throws Exception {
        String requestId = UUID.randomUUID().toString();
        Packet response = connection.request(Packet.ofJson(MessageType.LOGIN_REQ,
                JsonUtil.toJson(new LoginRequest(requestId, username, password))))
                .get(10, TimeUnit.SECONDS);
        LoginResponse login = JsonUtil.fromJson(response.payload(), LoginResponse.class);
        if (login.code() == ErrorCode.OK.code()) {
            this.userId = login.userId();
            connection.markAuthenticated();
        }
        return login;
    }

    public RegisterResponse register(String username, String password) throws Exception {
        String requestId = UUID.randomUUID().toString();
        Packet response = connection.request(Packet.ofJson(MessageType.REGISTER_REQ,
                JsonUtil.toJson(new RegisterRequest(requestId, username, password))))
                .get(10, TimeUnit.SECONDS);
        return JsonUtil.fromJson(response.payload(), RegisterResponse.class);
    }

    public MsgAck sendPrivate(long toUserId, String content) throws Exception {
        String requestId = UUID.randomUUID().toString();
        Packet response = connection.request(Packet.ofJson(MessageType.PRIVATE_MSG,
                JsonUtil.toJson(new PrivateMsg(requestId, UUID.randomUUID().toString(), userId,
                        toUserId, content, System.currentTimeMillis()))))
                .get(10, TimeUnit.SECONDS);
        return JsonUtil.fromJson(response.payload(), MsgAck.class);
    }

    public long userId() {
        return userId;
    }

    @Override
    public void close() {
        connection.close();
    }
}
