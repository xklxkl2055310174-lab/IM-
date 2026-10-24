package com.im.server;

import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.ProtocolException;
import com.im.common.json.JsonUtil;
import com.im.common.message.AccountMessages.LoginRequest;
import com.im.common.message.AccountMessages.LoginResponse;
import com.im.common.message.AccountMessages.LogoutRequest;
import com.im.common.message.AccountMessages.LogoutResponse;
import com.im.common.message.AccountMessages.RegisterRequest;
import com.im.common.message.AccountMessages.RegisterResponse;
import com.im.common.message.AccountMessages.ReloginRequest;
import com.im.common.message.AccountMessages.ReloginResponse;
import com.im.common.message.ChatMessages.DeliverAck;
import com.im.common.message.ChatMessages.MsgAck;
import com.im.common.message.ChatMessages.OfflineMsgItem;
import com.im.common.message.ChatMessages.OfflineMsgPush;
import com.im.common.message.ChatMessages.PrivateMsg;
import com.im.common.message.SystemMessages.KickNotify;
import com.im.common.message.SystemMessages.Ping;
import com.im.common.message.SystemMessages.Pong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 服务端业务核心：账号、会话、消息路由、离线投递。
 *
 * <p>阶段 1 存储全部在内存（总纲允许），阶段 2 换成 JDBC + H2/MySQL 时只替换仓储层，
 * 协议与 DTO 不变。</p>
 *
 * <p>线程模型：本类方法均由各连接的读线程调用，共享状态使用并发容器 + 以用户为粒度的同步块；
 * 禁止在持锁期间阻塞 IO（发送走连接自己的写队列）。</p>
 */
public final class Server {

    private static final Logger log = LoggerFactory.getLogger(Server.class);

    /** 离线消息每批条数（总纲：每批 100 条，确认一批再推下一批）。 */
    private static final int OFFLINE_BATCH = 100;

    private final ServerConfig config;
    private final Map<String, UserRecord> usersByName = new ConcurrentHashMap<>();
    private final Map<Long, UserRecord> usersById = new ConcurrentHashMap<>();
    private final Map<String, Long> tokenOwners = new ConcurrentHashMap<>();
    private final Map<Long, Connection> online = new ConcurrentHashMap<>();
    private final Map<Long, List<OfflineMsgItem>> inbox = new ConcurrentHashMap<>();
    private final AtomicLong userIdSeq = new AtomicLong(1000);
    private final AtomicLong inboxSeq = new AtomicLong(1);

    /** 用户记录（阶段 1 内存实现）。 */
    private record UserRecord(long id, String username, String salt, String hash) {
    }

    public Server(ServerConfig config) {
        this.config = config;
    }

    public ServerConfig config() {
        return config;
    }

    /** 处理一条报文，返回需要回给发送者的帧（可为 null）。 */
    public Packet handle(Connection conn, Packet packet) {
        try {
            return switch (packet.type()) {
                case REGISTER_REQ -> onRegister(packet);
                case LOGIN_REQ -> onLogin(conn, packet);
                case RELOGIN_REQ -> onRelogin(conn, packet);
                case LOGOUT_REQ -> onLogout(conn, packet);
                case PING -> onPing(packet);
                case PRIVATE_MSG -> onPrivateMsg(conn, packet);
                case DELIVER_ACK -> onDeliverAck(conn, packet);
                default -> error("", ErrorCode.INVALID_PAYLOAD, "阶段 1 未实现该消息: " + packet.type());
            };
        } catch (ProtocolException e) {
            log.warn("协议错误: {}", e.getMessage());
            return error("", e.errorCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("处理报文异常", e);
            return error("", ErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    // ---------- 账号 ----------

    private Packet onRegister(Packet packet) throws ProtocolException {
        RegisterRequest req = JsonUtil.fromJson(packet.payload(), RegisterRequest.class);
        if (req.username() == null || req.username().isBlank() || req.password() == null || req.password().isBlank()) {
            return error(req.requestId(), ErrorCode.INVALID_PAYLOAD, "用户名或密码为空");
        }
        if (usersByName.containsKey(req.username())) {
            return json(MessageType.REGISTER_RESP,
                    new RegisterResponse(req.requestId(), ErrorCode.USERNAME_EXISTS.code(), "用户名已存在", 0));
        }
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(req.password(), salt, config.pbkdf2Iterations());
        long id = userIdSeq.incrementAndGet();
        UserRecord record = new UserRecord(id, req.username(), salt, hash);
        usersByName.put(req.username(), record);
        usersById.put(id, record);
        log.info("注册用户: {} (id={})", req.username(), id);
        return json(MessageType.REGISTER_RESP,
                new RegisterResponse(req.requestId(), ErrorCode.OK.code(), "注册成功", id));
    }

    private Packet onLogin(Connection conn, Packet packet) throws ProtocolException {
        LoginRequest req = JsonUtil.fromJson(packet.payload(), LoginRequest.class);
        UserRecord user = req.username() == null ? null : usersByName.get(req.username());
        if (user == null || !PasswordHasher.verify(req.password() == null ? "" : req.password(),
                user.salt(), config.pbkdf2Iterations(), user.hash())) {
            return json(MessageType.LOGIN_RESP,
                    new LoginResponse(req.requestId(), ErrorCode.PASSWORD_ERROR.code(), "用户名或密码错误", 0, "", ""));
        }

        String token = UUID.randomUUID().toString();
        tokenOwners.put(token, user.id());
        conn.setUserId(user.id());
        conn.setUsername(user.username());
        conn.setToken(token);

        Connection old = online.put(user.id(), conn);
        if (old != null && old != conn && old.isOpen()) {
            old.send(json(MessageType.KICK_NOTIFY,
                    new KickNotify(ErrorCode.DUPLICATE_LOGIN.code(), "账号已在别处登录")));
            old.close();
            log.info("顶号: {} 旧连接被踢", user.username());
        }
        log.info("登录: {} (id={})", user.username(), user.id());

        conn.send(json(MessageType.LOGIN_RESP, new LoginResponse(req.requestId(),
                ErrorCode.OK.code(), "登录成功", user.id(), user.username(), token)));
        pushOffline(conn);
        return null;
    }

    private Packet onRelogin(Connection conn, Packet packet) throws ProtocolException {
        ReloginRequest req = JsonUtil.fromJson(packet.payload(), ReloginRequest.class);
        Long userId = req.token() == null ? null : tokenOwners.get(req.token());
        UserRecord user = userId == null ? null : usersById.get(userId);
        if (user == null) {
            return json(MessageType.RELOGIN_RESP,
                    new ReloginResponse(req.requestId(), ErrorCode.NOT_LOGGED_IN.code(), "token 无效或已过期", 0, ""));
        }
        conn.setUserId(user.id());
        conn.setUsername(user.username());
        conn.setToken(req.token());
        online.put(user.id(), conn);
        log.info("重登: {} (id={})", user.username(), user.id());
        conn.send(json(MessageType.RELOGIN_RESP, new ReloginResponse(req.requestId(),
                ErrorCode.OK.code(), "重登成功", user.id(), user.username())));
        pushOffline(conn);
        return null;
    }

    private Packet onLogout(Connection conn, Packet packet) throws ProtocolException {
        LogoutRequest req = JsonUtil.fromJson(packet.payload(), LogoutRequest.class);
        unregister(conn);
        conn.close();
        return json(MessageType.LOGOUT_RESP, new LogoutResponse(req.requestId(), ErrorCode.OK.code(), "已退出"));
    }

    // ---------- 心跳 ----------

    private Packet onPing(Packet packet) throws ProtocolException {
        Ping ping = JsonUtil.fromJson(packet.payload(), Ping.class);
        return json(MessageType.PONG, new Pong(ping.requestId(), ping.timestamp()));
    }

    // ---------- 消息 ----------

    private Packet onPrivateMsg(Connection conn, Packet packet) throws ProtocolException {
        if (conn.userId() == 0) {
            return error("", ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        PrivateMsg msg = JsonUtil.fromJson(packet.payload(), PrivateMsg.class);
        if (msg.toUserId() <= 0) {
            return error(msg.requestId(), ErrorCode.USER_NOT_FOUND, "接收者非法");
        }
        // 服务端以会话身份覆盖发送者
        PrivateMsg delivered = new PrivateMsg(msg.requestId(), msg.msgId(), conn.userId(),
                msg.toUserId(), msg.content(), msg.timestamp());

        Connection target = online.get(msg.toUserId());
        if (target != null && target.isOpen()) {
            target.send(json(MessageType.PRIVATE_MSG, delivered));
        } else {
            inbox.computeIfAbsent(msg.toUserId(), k -> new ArrayList<>())
                    .add(new OfflineMsgItem(inboxSeq.getAndIncrement(), msg.msgId(),
                            conn.userId(), msg.content(), System.currentTimeMillis()));
        }
        return json(MessageType.MSG_ACK, new MsgAck(msg.requestId(), msg.msgId(),
                ErrorCode.OK.code(), "已接收"));
    }

    private Packet onDeliverAck(Connection conn, Packet packet) throws ProtocolException {
        DeliverAck ack = JsonUtil.fromJson(packet.payload(), DeliverAck.class);
        conn.unacked().remove(ack.msgId());
        if (conn.unacked().isEmpty() && !conn.offlinePending().isEmpty()) {
            pushOffline(conn);
        }
        return null;
    }

    /** 推送一批离线消息（≤100 条）；全部确认后再推下一批。 */
    private void pushOffline(Connection conn) {
        List<OfflineMsgItem> source = inbox.get(conn.userId());
        if (source == null || source.isEmpty()) {
            return;
        }
        List<OfflineMsgItem> batch = new ArrayList<>(Math.min(OFFLINE_BATCH, source.size()));
        while (!source.isEmpty() && batch.size() < OFFLINE_BATCH) {
            batch.add(source.remove(0));
        }
        if (source.isEmpty()) {
            inbox.remove(conn.userId());
        }
        batch.forEach(item -> {
            conn.offlinePending().addLast(item);
            conn.unacked().add(item.msgId());
        });
        long lastId = batch.isEmpty() ? 0 : batch.get(batch.size() - 1).id();
        conn.send(json(MessageType.OFFLINE_MSG_PUSH, new OfflineMsgPush(batch, batch.size(), lastId)));
        log.info("推送离线消息 {} 条给用户 {}", batch.size(), conn.userId());
    }

    // ---------- 工具 ----------

    private Packet json(MessageType type, Object body) {
        return Packet.ofJson(type, JsonUtil.toJson(body));
    }

    private Packet error(String requestId, ErrorCode code, String message) {
        return json(MessageType.ERROR,
                new com.im.common.message.SystemMessages.ErrorMessage(requestId, code.code(), message));
    }

    /** 连接断开：下线并把未确认的离线消息退回收件箱。 */
    public void unregister(Connection conn) {
        if (conn.userId() > 0) {
            online.remove(conn.userId(), conn);
            List<OfflineMsgItem> back = new ArrayList<>(conn.offlinePending());
            if (!back.isEmpty()) {
                inbox.computeIfAbsent(conn.userId(), k -> new ArrayList<>()).addAll(0, back);
                conn.offlinePending().clear();
            }
            log.info("断开: {} (id={})", conn.username(), conn.userId());
        }
    }
}
