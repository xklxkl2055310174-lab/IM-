package com.im.common.codec;

import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.ProtocolException;
import com.im.common.json.JsonUtil;
import com.im.common.message.AccountMessages.LoginRequest;
import com.im.common.message.ChatMessages.PrivateMsg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PacketCodec} 帧编解码测试。
 *
 * <p>覆盖总纲要求的六类场景：编解码往返、粘包、半包、超大帧、魔数错误、未知类型，
 * 另补充空流、截断帧、帧头读取等边界。</p>
 */
class PacketCodecTest {

    private final PacketCodec codec = PacketCodec.DEFAULT;

    // ---------- 工具 ----------

    private static byte[] encode(Packet packet) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PacketCodec.DEFAULT.write(packet, new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static Packet decode(byte[] bytes) throws Exception {
        return PacketCodec.DEFAULT.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    /** 手工拼一个帧头，用于构造非法帧。 */
    private static byte[] rawFrame(int magic, int version, int type, int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(magic);
        out.writeByte(version);
        out.writeByte(type);
        out.writeInt(length);
        out.flush();
        return bytes.toByteArray();
    }

    /** 慢速输入流：每次最多返回 chunkSize 字节，模拟 TCP 半包。 */
    private static final class SlowInputStream extends InputStream {
        private final byte[] source;
        private final int chunkSize;
        private int pos;

        SlowInputStream(byte[] source, int chunkSize) {
            this.source = source;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() {
            if (pos >= source.length) {
                return -1;
            }
            return source[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= source.length) {
                return -1;
            }
            int n = Math.min(Math.min(chunkSize, len), source.length - pos);
            System.arraycopy(source, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    // ---------- 1. 编解码往返 ----------

    @Test
    @DisplayName("往返：类型与负载原样还原")
    void roundTrip() throws Exception {
        String json = JsonUtil.toJson(new LoginRequest("req-1", "alice", "123456"));
        Packet original = Packet.ofJson(MessageType.LOGIN_REQ, json);

        Packet decoded = decode(encode(original));

        assertEquals(MessageType.LOGIN_REQ, decoded.type());
        assertEquals(json, decoded.payloadAsString());
        assertEquals(original.payloadLength(), decoded.payloadLength());
    }

    @Test
    @DisplayName("往返：空负载（PONG 无业务字段）")
    void roundTripEmptyPayload() throws Exception {
        Packet decoded = decode(encode(Packet.empty(MessageType.PONG)));
        assertEquals(MessageType.PONG, decoded.type());
        assertEquals(0, decoded.payloadLength());
    }

    @Test
    @DisplayName("往返：UTF-8 中文负载的声明长度等于字节数")
    void roundTripUtf8() throws Exception {
        String json = JsonUtil.toJson(new PrivateMsg("req-2", "uuid-1", 1, 2, "你好，世界😀", 1700000000000L));
        byte[] bytes = encode(Packet.ofJson(MessageType.PRIVATE_MSG, json));

        // 帧头 8 字节：magic(2) ver(1) type(1) length(4)
        int declaredLength = ((bytes[4] & 0xFF) << 24) | ((bytes[5] & 0xFF) << 16)
                | ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        assertEquals(json.getBytes(StandardCharsets.UTF_8).length, declaredLength);
        assertEquals(json, decode(bytes).payloadAsString());
    }

    // ---------- 2. 粘包 ----------

    @Test
    @DisplayName("粘包：三帧一次写入，逐个读出且顺序不变")
    void stickyPackets() throws Exception {
        Packet p1 = Packet.ofJson(MessageType.LOGIN_REQ, "{\"a\":1}");
        Packet p2 = Packet.ofJson(MessageType.PRIVATE_MSG, "{\"b\":2}");
        Packet p3 = Packet.empty(MessageType.PONG);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        codec.write(p1, out);
        codec.write(p2, out);
        codec.write(p3, out);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(MessageType.LOGIN_REQ, codec.read(in).type());
        assertEquals("{\"b\":2}", codec.read(in).payloadAsString());
        assertEquals(MessageType.PONG, codec.read(in).type());
        assertNull(codec.read(in));
    }

    // ---------- 3. 半包 ----------

    @Test
    @DisplayName("半包：每次 1 字节输入仍能解出完整帧")
    void splitPacketsOneByteAtATime() throws Exception {
        String json = JsonUtil.toJson(new PrivateMsg("req-3", "uuid-2", 1001, 1002, "hello", 1700000000000L));
        byte[] frame = encode(Packet.ofJson(MessageType.PRIVATE_MSG, json));

        Packet decoded = codec.read(new DataInputStream(new SlowInputStream(frame, 1)));

        assertEquals(MessageType.PRIVATE_MSG, decoded.type());
        assertEquals(json, decoded.payloadAsString());
    }

    @Test
    @DisplayName("半包：每次 3 字节输入，连续两帧都能解出")
    void splitPacketsThreeBytesAtATime() throws Exception {
        byte[] f1 = encode(Packet.ofJson(MessageType.LOGIN_REQ, "{\"a\":1}"));
        byte[] f2 = encode(Packet.ofJson(MessageType.PRIVATE_MSG, "{\"b\":2}"));
        byte[] all = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, all, 0, f1.length);
        System.arraycopy(f2, 0, all, f1.length, f2.length);

        DataInputStream in = new DataInputStream(new SlowInputStream(all, 3));
        assertEquals(MessageType.LOGIN_REQ, codec.read(in).type());
        assertEquals(MessageType.PRIVATE_MSG, codec.read(in).type());
        assertNull(codec.read(in));
    }

    // ---------- 4. 超大帧 ----------

    @Test
    @DisplayName("超大帧：写超过 1 MiB 的负载被拒绝")
    void writeOversizePayloadRejected() {
        byte[] body = new byte[PacketCodec.MAX_PAYLOAD_LENGTH + 1];
        ProtocolException ex = assertThrows(ProtocolException.class,
                () -> encode(Packet.of(MessageType.PRIVATE_MSG, body)));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.errorCode());
    }

    @Test
    @DisplayName("超大帧：声明长度超过 1 MiB 的帧被拒绝")
    void readOversizeLengthRejected() throws Exception {
        byte[] frame = rawFrame(PacketCodec.MAGIC, PacketCodec.VERSION,
                MessageType.PRIVATE_MSG.code(), PacketCodec.MAX_PAYLOAD_LENGTH + 1024);

        ProtocolException ex = assertThrows(ProtocolException.class, () -> decode(frame));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.errorCode());
    }

    @Test
    @DisplayName("超大帧：负的长度被拒绝")
    void readNegativeLengthRejected() throws Exception {
        byte[] frame = rawFrame(PacketCodec.MAGIC, PacketCodec.VERSION, MessageType.PRIVATE_MSG.code(), -1);

        ProtocolException ex = assertThrows(ProtocolException.class, () -> decode(frame));
        assertEquals(ErrorCode.FRAME_TOO_LARGE, ex.errorCode());
    }

    // ---------- 5. 魔数 / 版本 ----------

    @Test
    @DisplayName("魔数错误：非法 magic 被拒绝")
    void badMagicRejected() throws Exception {
        byte[] frame = rawFrame(0xDEAD, PacketCodec.VERSION, MessageType.LOGIN_REQ.code(), 0);

        ProtocolException ex = assertThrows(ProtocolException.class, () -> decode(frame));
        assertEquals(ErrorCode.INVALID_FRAME, ex.errorCode());
    }

    @Test
    @DisplayName("版本不支持：合法魔数 + 未知版本被拒绝")
    void unsupportedVersionRejected() throws Exception {
        byte[] frame = rawFrame(PacketCodec.MAGIC, 99, MessageType.LOGIN_REQ.code(), 0);

        ProtocolException ex = assertThrows(ProtocolException.class, () -> decode(frame));
        assertEquals(ErrorCode.INVALID_FRAME, ex.errorCode());
    }

    // ---------- 6. 未知类型 ----------

    @Test
    @DisplayName("未知类型：未定义的 type 编码被拒绝")
    void unknownTypeRejected() throws Exception {
        byte[] frame = rawFrame(PacketCodec.MAGIC, PacketCodec.VERSION, 0x7E, 2);

        ProtocolException ex = assertThrows(ProtocolException.class, () -> decode(frame));
        assertEquals(ErrorCode.UNKNOWN_MESSAGE_TYPE, ex.errorCode());
    }

    // ---------- 补充边界 ----------

    @Test
    @DisplayName("空流：帧边界处返回 null 表示正常关闭")
    void emptyStreamReturnsNull() throws Exception {
        assertNull(codec.read(new DataInputStream(new ByteArrayInputStream(new byte[0]))));
    }

    @Test
    @DisplayName("截断帧：帧中途 EOF 视为错误")
    void truncatedFrameRejected() throws Exception {
        byte[] frame = encode(Packet.ofJson(MessageType.PRIVATE_MSG, "{\"a\":1}"));
        byte[] truncated = Arrays.copyOf(frame, frame.length - 2);

        assertThrows(EOFException.class, () -> decode(truncated));
    }

    @Test
    @DisplayName("帧头只读：readHeader 不消费 body")
    void readHeaderLeavesPayload() throws Exception {
        String json = "{\"hello\":\"world\"}";
        byte[] frame = encode(Packet.ofJson(MessageType.PRIVATE_MSG, json));

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
        PacketCodec.FrameHeader header = codec.readHeader(in);

        assertEquals(MessageType.PRIVATE_MSG, header.type());
        assertEquals(json.getBytes(StandardCharsets.UTF_8).length, header.payloadLength());
        byte[] body = new byte[header.payloadLength()];
        in.readFully(body);
        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), body);
    }
}
