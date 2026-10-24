package com.im.server.support;

import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.codec.PacketCodec;
import com.im.common.json.JsonUtil;
import com.im.common.message.AccountMessages.LoginRequest;
import com.im.common.message.AccountMessages.LoginResponse;
import com.im.common.message.AccountMessages.RegisterRequest;
import com.im.common.message.AccountMessages.RegisterResponse;
import com.im.common.message.ChatMessages.DeliverAck;
import com.im.common.message.ChatMessages.GroupMsg;
import com.im.common.message.ChatMessages.HistoryRequest;
import com.im.common.message.ChatMessages.HistoryResponse;
import com.im.common.message.ChatMessages.MsgAck;
import com.im.common.message.ChatMessages.PrivateMsg;
import com.im.common.message.ContactMessages.AddFriendRequest;
import com.im.common.message.ContactMessages.AddFriendResponse;
import com.im.common.message.ContactMessages.FriendListRequest;
import com.im.common.message.ContactMessages.FriendListResponse;
import com.im.common.message.GroupMessages.GroupCreateRequest;
import com.im.common.message.GroupMessages.GroupCreateResponse;
import com.im.common.message.GroupMessages.GroupJoinRequest;
import com.im.common.message.GroupMessages.GroupJoinResponse;
import com.im.common.message.GroupMessages.GroupListRequest;
import com.im.common.message.GroupMessages.GroupListResponse;
import com.im.common.message.GroupMessages.GroupMembersRequest;
import com.im.common.message.GroupMessages.GroupMembersResponse;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 测试用最小客户端：同步请求 + 推送缓存。
 */
public final class TestClient implements AutoCloseable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final List<Packet> pushes = new ArrayList<>();

    public long userId;
    public String username;
    public String token;

    public long userId() {
        return userId;
    }

    public String username() {
        return username;
    }

    public String token() {
        return token;
    }

    public TestClient(int port) throws Exception {
        this.socket = new Socket("127.0.0.1", port);
        this.socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    // ---------- 基础 ----------

    private void send(MessageType type, Object body) throws Exception {
        PacketCodec.DEFAULT.write(Packet.ofJson(type, JsonUtil.toJson(body)), out);
    }

    private Packet readOne(Duration timeout) throws Exception {
        socket.setSoTimeout((int) timeout.toMillis());
        try {
            return PacketCodec.DEFAULT.read(in);
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(0);
        }
    }

    /** 发送请求并等待指定响应（或 ERROR）；期间收到的其他帧进入推送缓存。 */
    public Packet request(MessageType type, Object body, MessageType expect) throws Exception {
        send(type, body);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Packet packet = readOne(Duration.ofSeconds(5));
            if (packet == null) {
                throw new IllegalStateException("等待 " + expect + " 超时");
            }
            if (packet.type() == expect || packet.type() == MessageType.ERROR) {
                return packet;
            }
            pushes.add(packet);
        }
        throw new IllegalStateException("等待 " + expect + " 超时");
    }

    /** ERROR 帧的 code；非 ERROR 返回 0。 */
    private static int errorCode(Packet packet) throws Exception {
        if (packet.type() != MessageType.ERROR) {
            return 0;
        }
        return JsonUtil.fromJson(packet.payload(),
                com.im.common.message.SystemMessages.ErrorMessage.class).code();
    }

    /** 等待某类推送（先查缓存，再读网络）。 */
    public Packet awaitPush(MessageType type, Duration timeout) throws Exception {
        for (int i = 0; i < pushes.size(); i++) {
            if (pushes.get(i).type() == type) {
                return pushes.remove(i);
            }
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remain = deadline - System.nanoTime();
            Packet packet = readOne(Duration.ofNanos(Math.max(1, remain)));
            if (packet == null) {
                return null;
            }
            if (packet.type() == type) {
                return packet;
            }
            pushes.add(packet);
        }
        return null;
    }

    /** 断言在给定时长内没有该类推送。 */
    public boolean noPush(MessageType type, Duration window) throws Exception {
        return awaitPush(type, window) == null;
    }

    public void sendDeliverAck(String msgId) throws Exception {
        send(MessageType.DELIVER_ACK, new DeliverAck(UUID.randomUUID().toString(), msgId));
    }

    // ---------- 账号 ----------

    public RegisterResponse register(String user, String password) throws Exception {
        Packet packet = request(MessageType.REGISTER_REQ,
                new RegisterRequest(UUID.randomUUID().toString(), user, password),
                MessageType.REGISTER_RESP);
        return JsonUtil.fromJson(packet.payload(), RegisterResponse.class);
    }

    public LoginResponse login(String user, String password) throws Exception {
        Packet packet = request(MessageType.LOGIN_REQ,
                new LoginRequest(UUID.randomUUID().toString(), user, password), MessageType.LOGIN_RESP);
        LoginResponse resp = JsonUtil.fromJson(packet.payload(), LoginResponse.class);
        if (resp.code() == ErrorCode.OK.code()) {
            this.userId = resp.userId();
            this.username = resp.username();
            this.token = resp.token();
        }
        return resp;
    }

    // ---------- 消息 ----------

    public MsgAck sendPrivate(long to, String content, String msgId) throws Exception {
        Packet packet = request(MessageType.PRIVATE_MSG,
                new PrivateMsg(UUID.randomUUID().toString(), msgId, userId, to, content, System.currentTimeMillis()),
                MessageType.MSG_ACK);
        if (packet.type() == MessageType.ERROR) {
            return new MsgAck("", msgId, errorCode(packet), errorText(packet));
        }
        return JsonUtil.fromJson(packet.payload(), MsgAck.class);
    }

    public MsgAck sendGroup(long groupId, String content, String msgId) throws Exception {
        Packet packet = request(MessageType.GROUP_MSG,
                new GroupMsg(UUID.randomUUID().toString(), msgId, userId, groupId, content, System.currentTimeMillis()),
                MessageType.MSG_ACK);
        if (packet.type() == MessageType.ERROR) {
            return new MsgAck("", msgId, errorCode(packet), errorText(packet));
        }
        return JsonUtil.fromJson(packet.payload(), MsgAck.class);
    }

    public HistoryResponse history(long peerId, int peerType, long beforeId, int limit) throws Exception {
        Packet packet = request(MessageType.HISTORY_REQ,
                new HistoryRequest(UUID.randomUUID().toString(), peerId, peerType, beforeId, limit),
                MessageType.HISTORY_RESP);
        if (packet.type() == MessageType.ERROR) {
            return new HistoryResponse("", errorCode(packet), errorText(packet), List.of());
        }
        return JsonUtil.fromJson(packet.payload(), HistoryResponse.class);
    }

    // ---------- 好友 / 群组 ----------

    public AddFriendResponse addFriend(String name) throws Exception {
        Packet packet = request(MessageType.ADD_FRIEND_REQ,
                new AddFriendRequest(UUID.randomUUID().toString(), name), MessageType.ADD_FRIEND_RESP);
        if (packet.type() == MessageType.ERROR) {
            return new AddFriendResponse("", errorCode(packet), errorText(packet), 0, "");
        }
        return JsonUtil.fromJson(packet.payload(), AddFriendResponse.class);
    }

    public FriendListResponse friends() throws Exception {
        Packet packet = request(MessageType.FRIEND_LIST_REQ,
                new FriendListRequest(UUID.randomUUID().toString()), MessageType.FRIEND_LIST_RESP);
        if (packet.type() == MessageType.ERROR) {
            return new FriendListResponse("", errorCode(packet), errorText(packet), List.of());
        }
        return JsonUtil.fromJson(packet.payload(), FriendListResponse.class);
    }

    public GroupCreateResponse createGroup(String name) throws Exception {
        Packet packet = request(MessageType.GROUP_CREATE_REQ,
                new GroupCreateRequest(UUID.randomUUID().toString(), name), MessageType.GROUP_CREATE_RESP);
        if (packet.type() == MessageType.ERROR) {
            return new GroupCreateResponse("", errorCode(packet), errorText(packet), 0, "");
        }
        return JsonUtil.fromJson(packet.payload(), GroupCreateResponse.class);
    }

    public GroupJoinResponse joinGroup(long groupId) throws Exception {
        Packet packet = request(MessageType.GROUP_JOIN_REQ,
                new GroupJoinRequest(UUID.randomUUID().toString(), groupId), MessageType.GROUP_JOIN_RESP);
        if (packet.type() == MessageType.ERROR) {
            return new GroupJoinResponse("", errorCode(packet), errorText(packet), groupId);
        }
        return JsonUtil.fromJson(packet.payload(), GroupJoinResponse.class);
    }

    public GroupListResponse groups() throws Exception {
        Packet packet = request(MessageType.GROUP_LIST_REQ,
                new GroupListRequest(UUID.randomUUID().toString()), MessageType.GROUP_LIST_RESP);
        if (packet.type() == MessageType.ERROR) {
            return new GroupListResponse("", errorCode(packet), errorText(packet), List.of());
        }
        return JsonUtil.fromJson(packet.payload(), GroupListResponse.class);
    }

    public GroupMembersResponse members(long groupId) throws Exception {
        Packet packet = request(MessageType.GROUP_MEMBERS_REQ,
                new GroupMembersRequest(UUID.randomUUID().toString(), groupId), MessageType.GROUP_MEMBERS_RESP);
        if (packet.type() == MessageType.ERROR) {
            return new GroupMembersResponse("", errorCode(packet), errorText(packet), List.of());
        }
        return JsonUtil.fromJson(packet.payload(), GroupMembersResponse.class);
    }

    private static String errorText(Packet packet) throws Exception {
        if (packet.type() != MessageType.ERROR) {
            return "";
        }
        return JsonUtil.fromJson(packet.payload(),
                com.im.common.message.SystemMessages.ErrorMessage.class).message();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
