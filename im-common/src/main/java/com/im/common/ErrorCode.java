package com.im.common;

/**
 * 统一错误码。
 *
 * <p>编码规则遵循总纲：账号类 1xxx、会话/权限类 2xxx、传输类 3xxx、内部错误 9xxx。
 * 1001/1002/2001/2002/3001 的语义已在总纲中固定，不得改动。</p>
 */
public enum ErrorCode {

    /** 成功。 */
    OK(0, "成功"),

    /** 用户名已存在（注册时）。 */
    USERNAME_EXISTS(1001, "用户名已存在"),

    /** 密码错误（登录时）。 */
    PASSWORD_ERROR(1002, "用户名或密码错误"),

    /** 帧格式非法：魔数或版本不匹配。 */
    INVALID_FRAME(1003, "帧格式非法"),

    /** 未知消息类型。 */
    UNKNOWN_MESSAGE_TYPE(1004, "未知消息类型"),

    /** 负载不是合法 JSON 或字段缺失。 */
    INVALID_PAYLOAD(1005, "报文负载非法"),

    /** 未登录（或登录态失效）。 */
    NOT_LOGGED_IN(2001, "尚未登录"),

    /** 无权限（如访问非好友会话、非群成员）。 */
    NO_PERMISSION(2002, "无权限"),

    /** 用户不存在。 */
    USER_NOT_FOUND(2003, "用户不存在"),

    /** 账号在别处登录，被顶下线。 */
    DUPLICATE_LOGIN(2004, "账号已在别处登录"),

    /** 群组不存在。 */
    GROUP_NOT_FOUND(2005, "群组不存在"),

    /** 文件不存在。 */
    FILE_NOT_FOUND(2006, "文件不存在"),

    /** 帧过大（length 超过上限）。 */
    FRAME_TOO_LARGE(3001, "帧过大"),

    /** 文件超过大小限制。 */
    FILE_TOO_LARGE(3002, "文件超过大小限制"),

    /** 传输失败（偏移不连续、写盘失败等）。 */
    TRANSFER_FAILED(3003, "文件传输失败"),

    /** 服务端繁忙。 */
    SERVER_BUSY(4001, "服务端繁忙"),

    /** 服务端内部错误。 */
    INTERNAL_ERROR(9000, "服务端内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** 按错误码查找；未知码返回 {@link #INTERNAL_ERROR}，避免抛异常。 */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return INTERNAL_ERROR;
    }
}
