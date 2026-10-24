package com.im.server.demo;

import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.codec.PacketCodec;
import com.im.common.json.JsonUtil;
import com.im.common.message.AccountMessages.LoginRequest;
import com.im.common.message.AccountMessages.LoginResponse;
import com.im.common.message.AccountMessages.RegisterRequest;
import com.im.common.message.AccountMessages.RegisterResponse;
import com.im.common.message.AccountMessages.ReloginRequest;
import com.im.common.message.AccountMessages.ReloginResponse;
import com.im.common.message.ChatMessages.DeliverAck;
import com.im.common.message.ChatMessages.GroupMsg;
import com.im.common.message.ChatMessages.HistoryRequest;
import com.im.common.message.ChatMessages.HistoryResponse;
import com.im.common.message.ChatMessages.MsgAck;
import com.im.common.message.ChatMessages.OfflineMsgPush;
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
import com.im.common.message.SystemMessages.ErrorMessage;
import com.im.server.file.FileDto.DownloadChunk;
import com.im.server.file.FileDto.DownloadStart;
import com.im.server.file.FileDto.UploadChunk;
import com.im.server.file.FileDto.UploadResult;
import com.im.server.file.FileDto.UploadStart;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 演示客户端（阶段 2-B）：验证可靠投递、好友、群组、历史与文件传输。
 *
 * <p>放在 im-server 内是因为本阶段约束"只修改 im-server"；后续阶段可迁到 im-client。</p>
 *
 * <pre>
 * 用法: java -cp im-server.jar com.im.server.demo.DemoClient --port 8080 --file-port 8081 --user alice
 * 命令:
 *   /msg &lt;toUserId&gt; &lt;文本&gt;        私聊
 *   /friend add &lt;username&gt;         加好友
 *   /friend list                   好友列表
 *   /group create &lt;name&gt;           建群
 *   /group join &lt;groupId&gt;          加群
 *   /group list                    我的群
 *   /group members &lt;groupId&gt;       群成员
 *   /group send &lt;groupId&gt; &lt;文本&gt;   群消息
 *   /history &lt;peerId&gt; [1|2] [beforeId]  历史（1 私聊 / 2 群聊）
 *   /file send &lt;toUserId&gt; &lt;path&gt;  上传文件
 *   /file get &lt;fileId&gt; &lt;outPath&gt;   下载文件
 *   /ping /me /quit
 * </pre>
 */
public final class DemoClient {

    private final String host;
    private final int port;
    private final int filePort;
    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private long userId;
    private String username;
    private String token;

    public DemoClient(String host, int port, int filePort) {
        this.host = host;
        this.port = port;
        this.filePort = filePort;
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 8080;
        int filePort = 8081;
        String user = "demo" + (int) (Math.random() * 1000);
        String password = "123456";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--file-port" -> filePort = Integer.parseInt(args[++i]);
                case "--user" -> user = args[++i];
                case "--pass" -> password = args[++i];
                case "--help", "-h" -> {
                    System.out.println("用法: java -cp im-server.jar com.im.server.demo.DemoClient "
                            + "[--host] [--port] [--file-port] [--user] [--pass]");
                    return;
                }
                default -> System.out.println("忽略未知参数: " + args[i]);
            }
        }

        DemoClient client = new DemoClient(host, port, filePort);
        client.connect();
        try {
            LoginResponse login = client.login(user, password);
            if (login.code() != ErrorCode.OK.code()) {
                RegisterResponse reg = client.register(user, password);
                if (reg.code() != ErrorCode.OK.code()) {
                    System.out.println("注册失败: " + reg.message());
                    return;
                }
                login = client.login(user, password);
            }
            if (login.code() != ErrorCode.OK.code()) {
                System.out.println("登录失败: " + login.message());
                return;
            }
            System.out.printf("已登录: %s (id=%d)%n", login.username(), login.userId());
            client.commandLoop();
        } finally {
            client.close();
        }
    }

    public void connect() throws Exception {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    // ---------- 基础请求 ----------

    private Packet request(MessageType type, Object body, MessageType expect) throws Exception {
        String requestId = UUID.randomUUID().toString();
        send(type, body);
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Packet packet = PacketCodec.DEFAULT.read(in);
            if (packet == null) {
                throw new IllegalStateException("连接已关闭");
            }
            if (packet.type() == expect) {
                return packet;
            }
            onPush(packet);
        }
        throw new IllegalStateException("等待 " + expect + " 超时");
    }

    private void send(MessageType type, Object body) throws Exception {
        PacketCodec.DEFAULT.write(Packet.ofJson(type, JsonUtil.toJson(body)), out);
    }

    private void onPush(Packet packet) {
        try {
            switch (packet.type()) {
                case PRIVATE_MSG -> {
                    PrivateMsg msg = JsonUtil.fromJson(packet.payload(), PrivateMsg.class);
                    System.out.printf("%n[私聊] %d: %s%n> ", msg.fromUserId(), msg.content());
                }
                case GROUP_MSG -> {
                    GroupMsg msg = JsonUtil.fromJson(packet.payload(), GroupMsg.class);
                    System.out.printf("%n[群 %d] %d: %s%n> ", msg.groupId(), msg.fromUserId(), msg.content());
                }
                case OFFLINE_MSG_PUSH -> {
                    OfflineMsgPush push = JsonUtil.fromJson(packet.payload(), OfflineMsgPush.class);
                    System.out.printf("%n[离线消息] %d 条%n", push.total());
                    for (var item : push.messages()) {
                        System.out.printf("  - id=%d from=%d: %s%n", item.id(), item.fromUserId(), item.content());
                        try {
                            send(MessageType.DELIVER_ACK, new DeliverAck(UUID.randomUUID().toString(), item.msgId()));
                        } catch (Exception e) {
                            System.out.println("  确认失败: " + e.getMessage());
                        }
                    }
                    System.out.print("> ");
                }
                case KICK_NOTIFY, SERVER_BUSY, ERROR -> System.out.printf("%n[系统] %s%n> ", packet.payloadAsString());
                default -> {
                    // 忽略其他推送
                }
            }
        } catch (Exception e) {
            System.out.println("[推送解析失败] " + e.getMessage());
        }
    }

    // ---------- 账号 ----------

    public LoginResponse login(String user, String password) throws Exception {
        String requestId = UUID.randomUUID().toString();
        Packet packet = request(MessageType.LOGIN_REQ, new LoginRequest(requestId, user, password),
                MessageType.LOGIN_RESP);
        LoginResponse resp = JsonUtil.fromJson(packet.payload(), LoginResponse.class);
        if (resp.code() == ErrorCode.OK.code()) {
            this.userId = resp.userId();
            this.username = resp.username();
            this.token = resp.token();
        }
        return resp;
    }

    public RegisterResponse register(String user, String password) throws Exception {
        Packet packet = request(MessageType.REGISTER_REQ,
                new RegisterRequest(UUID.randomUUID().toString(), user, password), MessageType.REGISTER_RESP);
        return JsonUtil.fromJson(packet.payload(), RegisterResponse.class);
    }

    // ---------- 命令循环 ----------

    public void commandLoop() throws Exception {
        System.out.println("命令: /msg /friend /group /history /file /ping /me /quit");
        java.io.BufferedReader console = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        while (true) {
            System.out.print("> ");
            String line = console.readLine();
            if (line == null || line.equals("/quit")) {
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                handle(line);
            } catch (Exception e) {
                System.out.println("执行失败: " + e.getMessage());
            }
        }
    }

    private void handle(String line) throws Exception {
        if (line.equals("/me")) {
            System.out.printf("用户名=%s id=%d token=%s%n", username, userId, token);
        } else if (line.equals("/ping")) {
            System.out.println("(心跳由服务端 90s 空闲检测，阶段 2 客户端未定时发送)");
        } else if (line.startsWith("/msg ")) {
            String[] parts = line.substring(5).trim().split("\\s+", 2);
            long to = Long.parseLong(parts[0]);
            Packet packet = request(MessageType.PRIVATE_MSG,
                    new PrivateMsg(UUID.randomUUID().toString(), UUID.randomUUID().toString(), userId, to,
                            parts.length > 1 ? parts[1] : "", System.currentTimeMillis()), MessageType.MSG_ACK);
            MsgAck ack = JsonUtil.fromJson(packet.payload(), MsgAck.class);
            System.out.printf("ACK %s code=%d %s%n", ack.msgId(), ack.code(), ack.message());
        } else if (line.startsWith("/friend add ")) {
            String name = line.substring(12).trim();
            Packet packet = request(MessageType.ADD_FRIEND_REQ,
                    new AddFriendRequest(UUID.randomUUID().toString(), name), MessageType.ADD_FRIEND_RESP);
            AddFriendResponse resp = JsonUtil.fromJson(packet.payload(), AddFriendResponse.class);
            System.out.printf("加好友: code=%d %s (%d/%s)%n", resp.code(), resp.message(),
                    resp.friendUserId(), resp.friendUsername());
        } else if (line.equals("/friend list")) {
            Packet packet = request(MessageType.FRIEND_LIST_REQ,
                    new FriendListRequest(UUID.randomUUID().toString()), MessageType.FRIEND_LIST_RESP);
            FriendListResponse resp = JsonUtil.fromJson(packet.payload(), FriendListResponse.class);
            resp.friends().forEach(f -> System.out.printf("  %d %s online=%s%n", f.userId(), f.username(), f.online()));
        } else if (line.startsWith("/group create ")) {
            String name = line.substring(14).trim();
            Packet packet = request(MessageType.GROUP_CREATE_REQ,
                    new GroupCreateRequest(UUID.randomUUID().toString(), name), MessageType.GROUP_CREATE_RESP);
            GroupCreateResponse resp = JsonUtil.fromJson(packet.payload(), GroupCreateResponse.class);
            System.out.printf("建群: code=%d %s groupId=%d%n", resp.code(), resp.message(), resp.groupId());
        } else if (line.startsWith("/group join ")) {
            long groupId = Long.parseLong(line.substring(12).trim());
            Packet packet = request(MessageType.GROUP_JOIN_REQ,
                    new GroupJoinRequest(UUID.randomUUID().toString(), groupId), MessageType.GROUP_JOIN_RESP);
            GroupJoinResponse resp = JsonUtil.fromJson(packet.payload(), GroupJoinResponse.class);
            System.out.printf("加群: code=%d %s%n", resp.code(), resp.message());
        } else if (line.equals("/group list")) {
            Packet packet = request(MessageType.GROUP_LIST_REQ,
                    new GroupListRequest(UUID.randomUUID().toString()), MessageType.GROUP_LIST_RESP);
            GroupListResponse resp = JsonUtil.fromJson(packet.payload(), GroupListResponse.class);
            resp.groups().forEach(g -> System.out.printf("  %d %s members=%d%n", g.groupId(), g.name(), g.memberCount()));
        } else if (line.startsWith("/group members ")) {
            long groupId = Long.parseLong(line.substring(15).trim());
            Packet packet = request(MessageType.GROUP_MEMBERS_REQ,
                    new GroupMembersRequest(UUID.randomUUID().toString(), groupId), MessageType.GROUP_MEMBERS_RESP);
            GroupMembersResponse resp = JsonUtil.fromJson(packet.payload(), GroupMembersResponse.class);
            if (resp.code() != 0) {
                System.out.println("失败: " + resp.message());
                return;
            }
            resp.members().forEach(m -> System.out.printf("  %d %s online=%s%n", m.userId(), m.username(), m.online()));
        } else if (line.startsWith("/group send ")) {
            String[] parts = line.substring(12).trim().split("\\s+", 2);
            long groupId = Long.parseLong(parts[0]);
            Packet packet = request(MessageType.GROUP_MSG, new GroupMsg(UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(), userId, groupId,
                            parts.length > 1 ? parts[1] : "", System.currentTimeMillis()),
                    MessageType.MSG_ACK);
            MsgAck ack = JsonUtil.fromJson(packet.payload(), MsgAck.class);
            System.out.printf("ACK %s code=%d %s%n", ack.msgId(), ack.code(), ack.message());
        } else if (line.startsWith("/history ")) {
            String[] parts = line.substring(9).trim().split("\\s+");
            long peerId = Long.parseLong(parts[0]);
            int peerType = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            long beforeId = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
            Packet packet = request(MessageType.HISTORY_REQ, new HistoryRequest(UUID.randomUUID().toString(),
                    peerId, peerType, beforeId, 50), MessageType.HISTORY_RESP);
            HistoryResponse resp = JsonUtil.fromJson(packet.payload(), HistoryResponse.class);
            if (resp.code() != 0) {
                System.out.println("失败: " + resp.message());
                return;
            }
            resp.messages().forEach(m -> System.out.printf("  id=%d from=%d: %s%n", m.id(), m.fromUserId(), m.content()));
        } else if (line.startsWith("/file send ")) {
            String[] parts = line.substring(11).trim().split("\\s+", 2);
            long to = Long.parseLong(parts[0]);
            upload(to, Path.of(parts[1]));
        } else if (line.startsWith("/file get ")) {
            String[] parts = line.substring(10).trim().split("\\s+", 2);
            download(parts[0], Path.of(parts[1]));
        } else {
            System.out.println("未知命令: " + line);
        }
    }

    // ---------- 文件 ----------

    /** 上传文件到文件端口。 */
    public String upload(long toUserId, Path file) throws Exception {
        byte[] data = Files.readAllBytes(file);
        String md5 = md5Hex(data);
        try (Socket fileSocket = new Socket(host, filePort)) {
            DataInputStream fin = new DataInputStream(new BufferedInputStream(fileSocket.getInputStream()));
            DataOutputStream fout = new DataOutputStream(new BufferedOutputStream(fileSocket.getOutputStream()));

            // 认证
            PacketCodec.DEFAULT.write(Packet.ofJson(MessageType.RELOGIN_REQ,
                    JsonUtil.toJson(new ReloginRequest(UUID.randomUUID().toString(), token))), fout);
            Packet auth = PacketCodec.DEFAULT.read(fin);
            ReloginResponse authResp = JsonUtil.fromJson(auth.payload(), ReloginResponse.class);
            if (authResp.code() != 0) {
                throw new IllegalStateException("文件端口认证失败: " + authResp.message());
            }

            String requestId = UUID.randomUUID().toString();
            PacketCodec.DEFAULT.write(Packet.ofJson(MessageType.FILE_UPLOAD_REQ,
                    JsonUtil.toJson(new UploadStart(requestId, file.getFileName().toString(), data.length, md5, toUserId))), fout);
            Packet startPacket = PacketCodec.DEFAULT.read(fin);
            UploadResult start = JsonUtil.fromJson(startPacket.payload(), UploadResult.class);
            if (start.code() != 0) {
                throw new IllegalStateException("上传申请失败: " + start.message());
            }
            String fileId = start.fileId();
            int chunk = 64 * 1024;
            for (int offset = 0; offset < data.length; offset += chunk) {
                int end = Math.min(offset + chunk, data.length);
                byte[] slice = java.util.Arrays.copyOfRange(data, offset, end);
                PacketCodec.DEFAULT.write(Packet.ofJson(MessageType.FILE_UPLOAD_REQ, JsonUtil.toJson(
                        new UploadChunk(requestId, fileId, offset, end >= data.length,
                                Base64.getEncoder().encodeToString(slice)))), fout);
                if (end >= data.length) {
                    Packet done = PacketCodec.DEFAULT.read(fin);
                    UploadResult result = JsonUtil.fromJson(done.payload(), UploadResult.class);
                    if (result.code() != 0) {
                        throw new IllegalStateException("上传失败: " + result.message());
                    }
                    System.out.printf("上传完成: fileId=%s bytes=%d md5=%s%n", fileId, result.uploadedBytes(), md5);
                    return fileId;
                }
            }
            throw new IllegalStateException("空文件");
        }
    }

    /** 从文件端口下载并校验 MD5。 */
    public boolean download(String fileId, Path target) throws Exception {
        try (Socket fileSocket = new Socket(host, filePort)) {
            DataInputStream fin = new DataInputStream(new BufferedInputStream(fileSocket.getInputStream()));
            DataOutputStream fout = new DataOutputStream(new BufferedOutputStream(fileSocket.getOutputStream()));

            PacketCodec.DEFAULT.write(Packet.ofJson(MessageType.RELOGIN_REQ,
                    JsonUtil.toJson(new ReloginRequest(UUID.randomUUID().toString(), token))), fout);
            PacketCodec.DEFAULT.read(fin);

            String requestId = UUID.randomUUID().toString();
            PacketCodec.DEFAULT.write(Packet.ofJson(MessageType.FILE_DOWNLOAD_REQ,
                    JsonUtil.toJson(new DownloadStart(requestId, fileId, 0))), fout);

            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            String fileName = "";
            while (true) {
                Packet packet = PacketCodec.DEFAULT.read(fin);
                if (packet == null) {
                    throw new IllegalStateException("下载连接关闭");
                }
                DownloadChunk chunk = JsonUtil.fromJson(packet.payload(), DownloadChunk.class);
                if (chunk.code() != 0) {
                    System.out.println("下载失败: " + chunk.message());
                    return false;
                }
                fileName = chunk.fileName();
                if (chunk.data() != null && !chunk.data().isEmpty()) {
                    buffer.write(Base64.getDecoder().decode(chunk.data()));
                }
                if (chunk.eof()) {
                    break;
                }
            }
            byte[] data = buffer.toByteArray();
            Files.write(target, data);
            System.out.printf("下载完成: %s -> %s (%d 字节, md5=%s)%n", fileName, target, data.length, md5Hex(data));
            return true;
        }
    }

    private static String md5Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }

    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
