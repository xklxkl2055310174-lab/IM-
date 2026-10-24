package com.im.common;

/**
 * 协议消息类型。
 *
 * <p>编码在阶段 1 确定并长期固定：只能追加新编码，禁止修改已发布编码或语义。
 * 编码按功能分段，便于排查与扩展。</p>
 */
public enum MessageType {

    // ---------- 账号 0x01-0x0F ----------
    /** 注册请求：C → S。 */
    REGISTER_REQ(0x01),
    /** 注册响应：S → C。 */
    REGISTER_RESP(0x02),
    /** 登录请求：C → S。 */
    LOGIN_REQ(0x03),
    /** 登录响应：S → C。 */
    LOGIN_RESP(0x04),
    /** token 重登请求：C → S。 */
    RELOGIN_REQ(0x05),
    /** token 重登响应：S → C。 */
    RELOGIN_RESP(0x06),
    /** 退出请求：C → S。 */
    LOGOUT_REQ(0x07),
    /** 退出响应：S → C。 */
    LOGOUT_RESP(0x08),

    // ---------- 心跳 0x10-0x1F ----------
    /** 心跳请求：C → S。 */
    PING(0x10),
    /** 心跳响应：S → C。 */
    PONG(0x11),

    // ---------- 消息 0x20-0x2F ----------
    /** 私聊消息（发送与推送共用）：C → S / S → C。 */
    PRIVATE_MSG(0x20),
    /** 群聊消息（发送与推送共用）：C → S / S → C。 */
    GROUP_MSG(0x21),
    /** 消息落库确认：S → C。 */
    MSG_ACK(0x22),
    /** 接收方投递确认：C → S。 */
    DELIVER_ACK(0x23),
    /** 离线消息推送：S → C。 */
    OFFLINE_MSG_PUSH(0x24),
    /** 历史消息拉取请求：C → S。 */
    HISTORY_REQ(0x25),
    /** 历史消息响应：S → C。 */
    HISTORY_RESP(0x26),

    // ---------- 联系人 0x30-0x3F ----------
    /** 好友列表请求：C → S。 */
    FRIEND_LIST_REQ(0x30),
    /** 好友列表响应：S → C。 */
    FRIEND_LIST_RESP(0x31),
    /** 添加好友请求：C → S。 */
    ADD_FRIEND_REQ(0x32),
    /** 添加好友响应：S → C。 */
    ADD_FRIEND_RESP(0x33),
    /** 在线用户列表请求：C → S。 */
    ONLINE_USERS_REQ(0x34),
    /** 在线用户列表响应：S → C。 */
    ONLINE_USERS_RESP(0x35),
    /** 用户上下线通知：S → C。 */
    USER_STATUS_NOTIFY(0x36),

    // ---------- 群组 0x40-0x4F ----------
    /** 建群请求：C → S。 */
    GROUP_CREATE_REQ(0x40),
    /** 建群响应：S → C。 */
    GROUP_CREATE_RESP(0x41),
    /** 加群请求：C → S。 */
    GROUP_JOIN_REQ(0x42),
    /** 加群响应：S → C。 */
    GROUP_JOIN_RESP(0x43),
    /** 群列表请求：C → S。 */
    GROUP_LIST_REQ(0x44),
    /** 群列表响应：S → C。 */
    GROUP_LIST_RESP(0x45),
    /** 群成员请求：C → S。 */
    GROUP_MEMBERS_REQ(0x46),
    /** 群成员响应：S → C。 */
    GROUP_MEMBERS_RESP(0x47),

    // ---------- 文件 0x50-0x5F（仅文件端口） ----------
    /** 文件上传请求：C → S。 */
    FILE_UPLOAD_REQ(0x50),
    /** 文件上传响应：S → C。 */
    FILE_UPLOAD_RESP(0x51),
    /** 文件下载请求：C → S。 */
    FILE_DOWNLOAD_REQ(0x52),
    /** 文件下载响应：S → C。 */
    FILE_DOWNLOAD_RESP(0x53),

    // ---------- 系统 0x60-0x7F ----------
    /** 被踢下线通知：S → C。 */
    KICK_NOTIFY(0x60),
    /** 服务端繁忙：S → C。 */
    SERVER_BUSY(0x61),
    /** 通用错误：S → C。 */
    ERROR(0x7F);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    /** 线上类型编码（1 字节）。 */
    public int code() {
        return code;
    }

    /**
     * 根据线上编码解析消息类型。
     *
     * @param code 1 字节编码
     * @return 对应枚举
     * @throws ProtocolException 编码未知
     */
    public static MessageType fromCode(int code) throws ProtocolException {
        for (MessageType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ProtocolException(ErrorCode.UNKNOWN_MESSAGE_TYPE, "未知消息类型: 0x" + Integer.toHexString(code));
    }
}
