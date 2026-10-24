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
import com.im.common.message.ChatMessages.GroupMsg;
import com.im.common.message.ChatMessages.HistoryRequest;
import com.im.common.message.ChatMessages.HistoryResponse;
import com.im.common.message.ChatMessages.MsgAck;
import com.im.common.message.ChatMessages.OfflineMsgItem;
import com.im.common.message.ChatMessages.OfflineMsgPush;
import com.im.common.message.ChatMessages.PrivateMsg;
import com.im.common.message.ContactMessages.AddFriendRequest;
import com.im.common.message.ContactMessages.AddFriendResponse;
import com.im.common.message.ContactMessages.FriendItem;
import com.im.common.message.ContactMessages.FriendListRequest;
import com.im.common.message.ContactMessages.FriendListResponse;
import com.im.common.message.ContactMessages.OnlineUser;
import com.im.common.message.ContactMessages.OnlineUsersRequest;
import com.im.common.message.ContactMessages.OnlineUsersResponse;
import com.im.common.message.GroupMessages.GroupCreateRequest;
import com.im.common.message.GroupMessages.GroupCreateResponse;
import com.im.common.message.GroupMessages.GroupItem;
import com.im.common.message.GroupMessages.GroupJoinRequest;
import com.im.common.message.GroupMessages.GroupJoinResponse;
import com.im.common.message.GroupMessages.GroupListRequest;
import com.im.common.message.GroupMessages.GroupListResponse;
import com.im.common.message.GroupMessages.GroupMember;
import com.im.common.message.GroupMessages.GroupMembersRequest;
import com.im.common.message.GroupMessages.GroupMembersResponse;
import com.im.common.message.SystemMessages.ErrorMessage;
import com.im.common.message.SystemMessages.KickNotify;
import com.im.common.message.SystemMessages.Ping;
import com.im.common.message.SystemMessages.Pong;
import com.im.server.db.Database;
import com.im.server.repo.MessageRepo;
import com.im.server.repo.SocialRepo;
import com.im.server.repo.UserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端业务核心：账号、可靠投递、好友、群组、历史消息。
 *
 * <p>可靠投递模型（总纲第 5 条）：</p>
 * <ol>
 *   <li>{@code msg_id} 唯一索引幂等：重复发送直接返回 ACK，不重复入库；</li>
 *   <li>消息本体落 {@code message} 表；</li>
 *   <li>为每个接收方写一条 {@code offline_message} 收件箱记录；</li>
 *   <li>接收方在线立即推送；收到 {@code DELIVER_ACK} 后删除该记录；</li>
 *   <li>离线消息登录后按 id 升序分批（100 条/批）推送，确认一批再推下一批；</li>
 *   <li>未确认即断线则记录保留，重登后再次推送（至少一次投递）。</li>
 * </ol>
 */
public final class Server {

    private static final Logger log = LoggerFactory.getLogger(Server.class);

    /** 离线消息每批条数。 */
    public static final int OFFLINE_BATCH = 100;

    private final ServerConfig config;
    private final Database database;
    private final UserRepo users;
    private final MessageRepo messages;
    private final SocialRepo social;

    private final Map<Long, Connection> online = new ConcurrentHashMap<>();
    private final Map<String, Long> tokenOwners = new ConcurrentHashMap<>();

    public Server(ServerConfig config, Database database) {
        this.config = config;
        this.database = database;
        this.users = new UserRepo(database);
        this.messages = new MessageRepo(database);
        this.social = new SocialRepo(database);
    }

    public ServerConfig config() {
        return config;
    }

    public Database database() {
        return database;
    }

    public MessageRepo messages() {
        return messages;
    }

    public SocialRepo social() {
        return social;
    }

    public UserRepo users() {
        return users;
    }

    /** 供文件端口复用登录态。 */
    public Long userIdByToken(String token) {
        return token == null ? null : tokenOwners.get(token);
    }

    public String usernameOf(long userId) {
        return users.findById(userId).map(UserRepo.User::username).orElse("");
    }

    public boolean isOnline(long userId) {
        Connection conn = online.get(userId);
        return conn != null && conn.isOpen();
    }

    // ---------- 分发 ----------

    public Packet handle(Connection conn, Packet packet) {
        try {
            return switch (packet.type()) {
                case REGISTER_REQ -> onRegister(packet);
                case LOGIN_REQ -> onLogin(conn, packet);
                case RELOGIN_REQ -> onRelogin(conn, packet);
                case LOGOUT_REQ -> onLogout(conn, packet);
                case PING -> onPing(packet);
                case PRIVATE_MSG -> onPrivateMsg(conn, packet);
                case GROUP_MSG -> onGroupMsg(conn, packet);
                case DELIVER_ACK -> onDeliverAck(conn, packet);
                case ADD_FRIEND_REQ -> onAddFriend(conn, packet);
                case FRIEND_LIST_REQ -> onFriendList(conn, packet);
                case ONLINE_USERS_REQ -> onOnlineUsers(conn, packet);
                case GROUP_CREATE_REQ -> onGroupCreate(conn, packet);
                case GROUP_JOIN_REQ -> onGroupJoin(conn, packet);
                case GROUP_LIST_REQ -> onGroupList(conn, packet);
                case GROUP_MEMBERS_REQ -> onGroupMembers(conn, packet);
                case HISTORY_REQ -> onHistory(conn, packet);
                default -> error("", ErrorCode.INVALID_PAYLOAD, "不支持的消息类型: " + packet.type());
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
        if (users.findByUsername(req.username()).isPresent()) {
            return json(MessageType.REGISTER_RESP, new RegisterResponse(req.requestId(),
                    ErrorCode.USERNAME_EXISTS.code(), "用户名已存在", 0));
        }
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(req.password(), salt, config.pbkdf2Iterations());
        long id = users.insert(req.username(), salt, hash, System.currentTimeMillis());
        log.info("注册用户: {} (id={})", req.username(), id);
        return json(MessageType.REGISTER_RESP, new RegisterResponse(req.requestId(),
                ErrorCode.OK.code(), "注册成功", id));
    }

    private Packet onLogin(Connection conn, Packet packet) throws ProtocolException {
        LoginRequest req = JsonUtil.fromJson(packet.payload(), LoginRequest.class);
        UserRepo.User user = req.username() == null ? null : users.findByUsername(req.username()).orElse(null);
        if (user == null || !PasswordHasher.verify(req.password() == null ? "" : req.password(),
                user.salt(), config.pbkdf2Iterations(), user.hash())) {
            return json(MessageType.LOGIN_RESP, new LoginResponse(req.requestId(),
                    ErrorCode.PASSWORD_ERROR.code(), "用户名或密码错误", 0, "", ""));
        }
        String token = UUID.randomUUID().toString();
        tokenOwners.put(token, user.id());
        bind(conn, user.id(), user.username(), token);
        log.info("登录: {} (id={})", user.username(), user.id());
        conn.send(json(MessageType.LOGIN_RESP, new LoginResponse(req.requestId(),
                ErrorCode.OK.code(), "登录成功", user.id(), user.username(), token)));
        pushOffline(conn);
        return null;
    }

    private Packet onRelogin(Connection conn, Packet packet) throws ProtocolException {
        ReloginRequest req = JsonUtil.fromJson(packet.payload(), ReloginRequest.class);
        Long userId = userIdByToken(req.token());
        if (userId == null) {
            return json(MessageType.RELOGIN_RESP, new ReloginResponse(req.requestId(),
                    ErrorCode.NOT_LOGGED_IN.code(), "token 无效或已过期", 0, ""));
        }
        String username = usernameOf(userId);
        bind(conn, userId, username, req.token());
        log.info("重登: {} (id={})", username, userId);
        conn.send(json(MessageType.RELOGIN_RESP, new ReloginResponse(req.requestId(),
                ErrorCode.OK.code(), "重登成功", userId, username)));
        pushOffline(conn);
        return null;
    }

    /** 登录态绑定，并处理顶号。 */
    private void bind(Connection conn, long userId, String username, String token) {
        conn.setUserId(userId);
        conn.setUsername(username);
        conn.setToken(token);
        Connection old = online.put(userId, conn);
        if (old != null && old != conn && old.isOpen()) {
            old.send(json(MessageType.KICK_NOTIFY,
                    new KickNotify(ErrorCode.DUPLICATE_LOGIN.code(), "账号已在别处登录")));
            old.close();
            log.info("顶号: {}", username);
        }
    }

    private Packet onLogout(Connection conn, Packet packet) throws ProtocolException {
        LogoutRequest req = JsonUtil.fromJson(packet.payload(), LogoutRequest.class);
        unregister(conn);
        conn.close();
        return json(MessageType.LOGOUT_RESP,
                new LogoutResponse(req.requestId(), ErrorCode.OK.code(), "已退出"));
    }

    private Packet onPing(Packet packet) throws ProtocolException {
        Ping ping = JsonUtil.fromJson(packet.payload(), Ping.class);
        return json(MessageType.PONG, new Pong(ping.requestId(), ping.timestamp()));
    }

    // ---------- 可靠投递 ----------

    private Packet onPrivateMsg(Connection conn, Packet packet) throws ProtocolException {
        if (conn.userId() == 0) {
            return error("", ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        PrivateMsg msg = JsonUtil.fromJson(packet.payload(), PrivateMsg.class);
        if (msg.msgId() == null || msg.msgId().isBlank()) {
            return error(msg.requestId(), ErrorCode.INVALID_PAYLOAD, "msgId 不能为空");
        }
        if (messages.existsMsgId(msg.msgId())) {
            // 幂等：同一 msgId 重发不重复入库，直接回 ACK
            log.info("重复消息 {}，直接 ACK", msg.msgId());
            return json(MessageType.MSG_ACK, new MsgAck(msg.requestId(), msg.msgId(),
                    ErrorCode.OK.code(), "重复消息，已忽略"));
        }
        if (msg.toUserId() <= 0 || users.findById(msg.toUserId()).isEmpty()) {
            return error(msg.requestId(), ErrorCode.USER_NOT_FOUND, "接收者不存在");
        }
        long now = System.currentTimeMillis();
        long messageId = messages.insertMessage(msg.msgId(), 1, conn.userId(), msg.toUserId(), null,
                msg.content(), now);
        messages.insertInbox(msg.toUserId(), messageId, msg.msgId(), conn.userId(), msg.content(), now);

        Connection target = online.get(msg.toUserId());
        if (target != null && target.isOpen()) {
            target.send(json(MessageType.PRIVATE_MSG, new PrivateMsg("", msg.msgId(), conn.userId(),
                    msg.toUserId(), msg.content(), msg.timestamp())));
        }
        return json(MessageType.MSG_ACK, new MsgAck(msg.requestId(), msg.msgId(),
                ErrorCode.OK.code(), "已接收"));
    }

    private Packet onGroupMsg(Connection conn, Packet packet) throws ProtocolException {
        if (conn.userId() == 0) {
            return error("", ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        GroupMsg msg = JsonUtil.fromJson(packet.payload(), GroupMsg.class);
        if (msg.msgId() == null || msg.msgId().isBlank()) {
            return error(msg.requestId(), ErrorCode.INVALID_PAYLOAD, "msgId 不能为空");
        }
        if (!social.groupExists(msg.groupId())) {
            return error(msg.requestId(), ErrorCode.GROUP_NOT_FOUND, "群不存在");
        }
        if (!social.isMember(msg.groupId(), conn.userId())) {
            return error(msg.requestId(), ErrorCode.NO_PERMISSION, "非群成员，不能发言");
        }
        if (messages.existsMsgId(msg.msgId())) {
            return json(MessageType.MSG_ACK, new MsgAck(msg.requestId(), msg.msgId(),
                    ErrorCode.OK.code(), "重复消息，已忽略"));
        }

        long now = System.currentTimeMillis();
        long messageId = messages.insertMessage(msg.msgId(), 2, conn.userId(), null, msg.groupId(),
                msg.content(), now);
        GroupMsg delivered = new GroupMsg("", msg.msgId(), conn.userId(), msg.groupId(),
                msg.content(), msg.timestamp());
        for (SocialRepo.MemberRow member : social.membersOf(msg.groupId())) {
            if (member.userId() == conn.userId()) {
                continue;
            }
            messages.insertInbox(member.userId(), messageId, msg.msgId(), conn.userId(), msg.content(), now);
            Connection target = online.get(member.userId());
            if (target != null && target.isOpen()) {
                target.send(json(MessageType.GROUP_MSG, delivered));
            }
        }
        return json(MessageType.MSG_ACK, new MsgAck(msg.requestId(), msg.msgId(),
                ErrorCode.OK.code(), "已接收"));
    }

    private Packet onDeliverAck(Connection conn, Packet packet) throws ProtocolException {
        DeliverAck ack = JsonUtil.fromJson(packet.payload(), DeliverAck.class);
        boolean deleted = messages.deleteInbox(conn.userId(), ack.msgId());
        if (deleted) {
            conn.ackDelivered();
        }
        if (conn.pendingAcks() <= 0 && messages.countInbox(conn.userId()) > 0) {
            pushOffline(conn);
        }
        return null;
    }

    /** 推送一批离线消息（≤100 条）。 */
    private void pushOffline(Connection conn) {
        List<MessageRepo.InboxRow> rows = messages.listInbox(conn.userId(), OFFLINE_BATCH);
        if (rows.isEmpty()) {
            return;
        }
        List<OfflineMsgItem> items = new ArrayList<>(rows.size());
        for (MessageRepo.InboxRow row : rows) {
            items.add(new OfflineMsgItem(row.id(), row.msgId(), row.fromUser(), row.content(), row.createdAt()));
        }
        conn.setPendingAcks(rows.size());
        long lastId = rows.get(rows.size() - 1).id();
        conn.send(json(MessageType.OFFLINE_MSG_PUSH, new OfflineMsgPush(items, items.size(), lastId)));
        log.info("推送离线消息 {} 条给用户 {}", items.size(), conn.userId());
    }

    // ---------- 好友 ----------

    private Packet onAddFriend(Connection conn, Packet packet) throws ProtocolException {
        if (conn.userId() == 0) {
            return error("", ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        AddFriendRequest req = JsonUtil.fromJson(packet.payload(), AddFriendRequest.class);
        UserRepo.User friend = req.username() == null ? null : users.findByUsername(req.username()).orElse(null);
        if (friend == null) {
            return json(MessageType.ADD_FRIEND_RESP, new AddFriendResponse(req.requestId(),
                    ErrorCode.USER_NOT_FOUND.code(), "用户不存在", 0, ""));
        }
        if (friend.id() == conn.userId()) {
            return json(MessageType.ADD_FRIEND_RESP, new AddFriendResponse(req.requestId(),
                    ErrorCode.NO_PERMISSION.code(), "不能添加自己", 0, ""));
        }
        social.addFriendPair(conn.userId(), friend.id(), System.currentTimeMillis());
        return json(MessageType.ADD_FRIEND_RESP, new AddFriendResponse(req.requestId(),
                ErrorCode.OK.code(), "已添加", friend.id(), friend.username()));
    }

    private Packet onFriendList(Connection conn, Packet packet) throws ProtocolException {
        FriendListRequest req = JsonUtil.fromJson(packet.payload(), FriendListRequest.class);
        if (conn.userId() == 0) {
            return error(req.requestId(), ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        List<FriendItem> items = new ArrayList<>();
        for (Long friendId : social.friendIds(conn.userId())) {
            items.add(new FriendItem(friendId, usernameOf(friendId), isOnline(friendId)));
        }
        return json(MessageType.FRIEND_LIST_RESP, new FriendListResponse(req.requestId(),
                ErrorCode.OK.code(), "ok", items));
    }

    private Packet onOnlineUsers(Connection conn, Packet packet) throws ProtocolException {
        OnlineUsersRequest req = JsonUtil.fromJson(packet.payload(), OnlineUsersRequest.class);
        if (conn.userId() == 0) {
            return error(req.requestId(), ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        List<OnlineUser> users = new ArrayList<>();
        for (Connection onlineConn : online.values()) {
            if (onlineConn.isOpen()) {
                users.add(new OnlineUser(onlineConn.userId(), onlineConn.username()));
            }
        }
        return json(MessageType.ONLINE_USERS_RESP, new OnlineUsersResponse(req.requestId(),
                ErrorCode.OK.code(), "ok", users));
    }

    // ---------- 群组 ----------

    private Packet onGroupCreate(Connection conn, Packet packet) throws ProtocolException {
        if (conn.userId() == 0) {
            return error("", ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        GroupCreateRequest req = JsonUtil.fromJson(packet.payload(), GroupCreateRequest.class);
        String name = req.name() == null || req.name().isBlank() ? "未命名群" : req.name();
        long groupId = social.createGroup(name, conn.userId(), System.currentTimeMillis());
        log.info("建群: {} (id={}) by {}", name, groupId, conn.username());
        return json(MessageType.GROUP_CREATE_RESP, new GroupCreateResponse(req.requestId(),
                ErrorCode.OK.code(), "建群成功", groupId, name));
    }

    private Packet onGroupJoin(Connection conn, Packet packet) throws ProtocolException {
        if (conn.userId() == 0) {
            return error("", ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        GroupJoinRequest req = JsonUtil.fromJson(packet.payload(), GroupJoinRequest.class);
        if (!social.groupExists(req.groupId())) {
            return json(MessageType.GROUP_JOIN_RESP, new GroupJoinResponse(req.requestId(),
                    ErrorCode.GROUP_NOT_FOUND.code(), "群不存在", req.groupId()));
        }
        social.join(req.groupId(), conn.userId(), System.currentTimeMillis());
        return json(MessageType.GROUP_JOIN_RESP, new GroupJoinResponse(req.requestId(),
                ErrorCode.OK.code(), "已加入", req.groupId()));
    }

    private Packet onGroupList(Connection conn, Packet packet) throws ProtocolException {
        GroupListRequest req = JsonUtil.fromJson(packet.payload(), GroupListRequest.class);
        if (conn.userId() == 0) {
            return error(req.requestId(), ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        List<GroupItem> items = new ArrayList<>();
        for (SocialRepo.GroupRow row : social.groupsOf(conn.userId())) {
            items.add(new GroupItem(row.id(), row.name(), row.memberCount()));
        }
        return json(MessageType.GROUP_LIST_RESP, new GroupListResponse(req.requestId(),
                ErrorCode.OK.code(), "ok", items));
    }

    private Packet onGroupMembers(Connection conn, Packet packet) throws ProtocolException {
        GroupMembersRequest req = JsonUtil.fromJson(packet.payload(), GroupMembersRequest.class);
        if (conn.userId() == 0) {
            return error(req.requestId(), ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        if (!social.groupExists(req.groupId())) {
            return error(req.requestId(), ErrorCode.GROUP_NOT_FOUND, "群不存在");
        }
        if (!social.isMember(req.groupId(), conn.userId())) {
            return error(req.requestId(), ErrorCode.NO_PERMISSION, "非群成员");
        }
        List<GroupMember> members = new ArrayList<>();
        for (SocialRepo.MemberRow row : social.membersOf(req.groupId())) {
            members.add(new GroupMember(row.userId(), row.username(), isOnline(row.userId())));
        }
        return json(MessageType.GROUP_MEMBERS_RESP, new GroupMembersResponse(req.requestId(),
                ErrorCode.OK.code(), "ok", members));
    }

    // ---------- 历史 ----------

    private Packet onHistory(Connection conn, Packet packet) throws ProtocolException {
        if (conn.userId() == 0) {
            return error("", ErrorCode.NOT_LOGGED_IN, "请先登录");
        }
        HistoryRequest req = JsonUtil.fromJson(packet.payload(), HistoryRequest.class);
        int limit = req.limit() <= 0 ? 50 : Math.min(req.limit(), 100);
        List<MessageRepo.HistoryRow> rows;
        if (req.peerType() == 2) {
            if (!social.groupExists(req.peerId())) {
                return error(req.requestId(), ErrorCode.GROUP_NOT_FOUND, "群不存在");
            }
            if (!social.isMember(req.peerId(), conn.userId())) {
                return error(req.requestId(), ErrorCode.NO_PERMISSION, "非群成员，无权查看历史");
            }
            rows = messages.historyGroup(req.peerId(), req.beforeId(), limit);
        } else {
            if (users.findById(req.peerId()).isEmpty()) {
                return error(req.requestId(), ErrorCode.USER_NOT_FOUND, "用户不存在");
            }
            rows = messages.historyPrivate(conn.userId(), req.peerId(), req.beforeId(), limit);
        }
        List<OfflineMsgItem> items = new ArrayList<>(rows.size());
        for (MessageRepo.HistoryRow row : rows) {
            items.add(new OfflineMsgItem(row.id(), row.msgId(), row.fromUser(), row.content(), row.createdAt()));
        }
        return json(MessageType.HISTORY_RESP, new HistoryResponse(req.requestId(),
                ErrorCode.OK.code(), "ok", items));
    }

    // ---------- 工具 ----------

    private Packet json(MessageType type, Object body) {
        return Packet.ofJson(type, JsonUtil.toJson(body));
    }

    private Packet error(String requestId, ErrorCode code, String message) {
        return json(MessageType.ERROR, new ErrorMessage(requestId, code.code(), message));
    }

    /** 连接断开：下线（未确认的收件箱记录保留在库中，下次登录重推）。 */
    public void unregister(Connection conn) {
        if (conn.userId() > 0) {
            online.remove(conn.userId(), conn);
            log.info("断开: {} (id={})", conn.username(), conn.userId());
        }
    }
}
