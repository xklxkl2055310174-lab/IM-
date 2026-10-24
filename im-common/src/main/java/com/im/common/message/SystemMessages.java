package com.im.common.message;

/**
 * 系统域 DTO：心跳、被踢通知、服务繁忙、通用错误。
 */
public final class SystemMessages {

    private SystemMessages() {
    }

    /**
     * 心跳请求（{@code PING}）。
     *
     * @param requestId 请求标识
     * @param timestamp 客户端时间（毫秒），PONG 原样回填用于计算 RTT
     */
    public record Ping(String requestId, long timestamp) {
    }

    /**
     * 心跳响应（{@code PONG}）。
     *
     * @param requestId 对应请求 id
     * @param timestamp 原样回填的客户端时间
     */
    public record Pong(String requestId, long timestamp) {
    }

    /**
     * 被踢下线通知（{@code KICK_NOTIFY}）：同账号在别处登录时，旧连接收到此消息后应停止重连。
     *
     * @param code    错误码，通常 2004
     * @param message 描述
     */
    public record KickNotify(int code, String message) {
    }

    /**
     * 服务端繁忙（{@code SERVER_BUSY}）：连接数超限时返回后关闭。
     *
     * @param code    错误码，通常 4001
     * @param message 描述
     */
    public record ServerBusy(int code, String message) {
    }

    /**
     * 通用错误（{@code ERROR}）。
     *
     * @param requestId 对应请求 id（无法关联时为空字符串）
     * @param code      错误码
     * @param message   描述
     */
    public record ErrorMessage(String requestId, int code, String message) {
    }
}
