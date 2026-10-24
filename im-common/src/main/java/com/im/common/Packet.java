package com.im.common;

import java.util.Objects;

/**
 * 一条协议报文：消息类型 + 原始负载字节。
 *
 * <p>负载约定为 UTF-8 编码的 JSON（见 {@code docs/protocol.md}），但 {@code Packet} 本身不感知
 * 业务结构，序列化交给 {@link com.im.common.json.JsonUtil}。</p>
 */
public final class Packet {

    private final MessageType type;
    private final byte[] payload;

    private Packet(MessageType type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    /**
     * 构造报文。
     *
     * @param type    消息类型，非空
     * @param payload 负载字节，允许为空数组（如心跳响应无业务字段）
     */
    public static Packet of(MessageType type, byte[] payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        return new Packet(type, payload);
    }

    /** 负载为 UTF-8 JSON 文本的便捷构造。 */
    public static Packet ofJson(MessageType type, String json) {
        Objects.requireNonNull(json, "json");
        return new Packet(type, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 负载为空报文（如心跳响应）。 */
    public static Packet empty(MessageType type) {
        return new Packet(type, new byte[0]);
    }

    public MessageType type() {
        return type;
    }

    public byte[] payload() {
        return payload;
    }

    public int payloadLength() {
        return payload.length;
    }

    /** 负载按 UTF-8 解码为字符串。 */
    public String payloadAsString() {
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "Packet{type=" + type + ", payloadLength=" + payload.length + '}';
    }
}
