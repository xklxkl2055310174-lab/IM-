package com.im.common.codec;

import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.ProtocolException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;

/**
 * 帧编解码器。
 *
 * <pre>
 * 帧格式（大端序）：
 * +----------+---------+--------+-----------+------------------+
 * | magic(2) | ver(1)  | type(1)| length(4) | body (length B)  |
 * +----------+---------+--------+-----------+------------------+
 * </pre>
 *
 * <p>magic 固定 {@code 0xCAFE}，version 固定 {@code 1}，length 为 body 字节数，
 * 上限 {@value #MAX_PAYLOAD_LENGTH} 字节（1 MiB）。</p>
 *
 * <p>实现无状态、线程安全，只依赖调用方给出的流对象。</p>
 */
public final class PacketCodec {

    /** 魔数（2 字节）。 */
    public static final int MAGIC = 0xCAFE;

    /** 协议版本。 */
    public static final int VERSION = 1;

    /** 单帧 body 上限：1 MiB。 */
    public static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;

    /** 帧头长度：2 + 1 + 1 + 4。 */
    public static final int HEADER_LENGTH = 8;

    /** 共享实例。 */
    public static final PacketCodec DEFAULT = new PacketCodec();

    /**
     * 写出一个完整帧。
     *
     * @throws ProtocolException body 超过 {@link #MAX_PAYLOAD_LENGTH}
     */
    public void write(Packet packet, DataOutputStream out) throws IOException, ProtocolException {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(out, "out");
        byte[] body = packet.payload();
        if (body.length > MAX_PAYLOAD_LENGTH) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE,
                    "帧体 " + body.length + " 字节超过上限 " + MAX_PAYLOAD_LENGTH);
        }
        out.writeShort(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(packet.type().code());
        out.writeInt(body.length);
        out.write(body);
        out.flush();
    }

    /**
     * 读出一个完整帧。
     *
     * @return 报文；{@code null} 表示流在帧边界处正常结束（对端关闭）
     * @throws ProtocolException 魔数/版本/类型/长度非法
     * @throws IOException 读取失败（帧中途 EOF 视为损坏）
     */
    public Packet read(DataInputStream in) throws IOException, ProtocolException {
        Objects.requireNonNull(in, "in");

        int magic;
        try {
            magic = in.readUnsignedShort();
        } catch (EOFException eof) {
            return null;
        }
        if (magic != MAGIC) {
            throw new ProtocolException(ErrorCode.INVALID_FRAME,
                    "魔数错误: 0x" + Integer.toHexString(magic) + "，期望 0x" + Integer.toHexString(MAGIC));
        }

        int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new ProtocolException(ErrorCode.INVALID_FRAME,
                    "版本不支持: " + version + "，期望 " + VERSION);
        }

        MessageType type = MessageType.fromCode(in.readUnsignedByte());

        int length = in.readInt();
        if (length < 0 || length > MAX_PAYLOAD_LENGTH) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE,
                    "帧长度非法: " + length + "，上限 " + MAX_PAYLOAD_LENGTH);
        }

        byte[] body = new byte[length];
        in.readFully(body);
        return Packet.of(type, body);
    }

    /**
     * 只读帧头，不消费 body。供"先看类型再决定是否消费"的高级场景使用。
     *
     * @return 帧头；{@code null} 表示流在帧边界结束
     */
    public FrameHeader readHeader(DataInputStream in) throws IOException, ProtocolException {
        int magic;
        try {
            magic = in.readUnsignedShort();
        } catch (EOFException eof) {
            return null;
        }
        if (magic != MAGIC) {
            throw new ProtocolException(ErrorCode.INVALID_FRAME,
                    "魔数错误: 0x" + Integer.toHexString(magic));
        }
        int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new ProtocolException(ErrorCode.INVALID_FRAME, "版本不支持: " + version);
        }
        MessageType type = MessageType.fromCode(in.readUnsignedByte());
        int length = in.readInt();
        if (length < 0 || length > MAX_PAYLOAD_LENGTH) {
            throw new ProtocolException(ErrorCode.FRAME_TOO_LARGE, "帧长度非法: " + length);
        }
        return new FrameHeader(type, length);
    }

    /** 帧头信息。 */
    public record FrameHeader(MessageType type, int payloadLength) {
    }
}
