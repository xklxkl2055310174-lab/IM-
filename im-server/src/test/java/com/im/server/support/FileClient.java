package com.im.server.support;

import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.codec.PacketCodec;
import com.im.common.json.JsonUtil;
import com.im.common.message.AccountMessages.ReloginRequest;
import com.im.common.message.AccountMessages.ReloginResponse;
import com.im.server.file.FileDto.DownloadChunk;
import com.im.server.file.FileDto.DownloadStart;
import com.im.server.file.FileDto.UploadChunk;
import com.im.server.file.FileDto.UploadResult;
import com.im.server.file.FileDto.UploadStart;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 测试用文件客户端：封装文件端口的认证、上传、下载。
 */
public final class FileClient implements AutoCloseable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final String token;

    public FileClient(int filePort, String token) throws Exception {
        this.token = token;
        this.socket = new Socket("127.0.0.1", filePort);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    private void send(MessageType type, Object body) throws Exception {
        PacketCodec.DEFAULT.write(Packet.ofJson(type, JsonUtil.toJson(body)), out);
    }

    private Packet read() throws Exception {
        return PacketCodec.DEFAULT.read(in);
    }

    /** 认证；返回错误码（0 成功）。 */
    public int authenticate() throws Exception {
        send(MessageType.RELOGIN_REQ, new ReloginRequest(UUID.randomUUID().toString(), token));
        ReloginResponse resp = JsonUtil.fromJson(read().payload(), ReloginResponse.class);
        return resp.code();
    }

    /** 上传：返回最终结果。 */
    public UploadResult upload(byte[] data, String fileName, Long toUserId) throws Exception {
        String requestId = UUID.randomUUID().toString();
        send(MessageType.FILE_UPLOAD_REQ,
                new UploadStart(requestId, fileName, data.length, md5(data), toUserId));
        UploadResult start = JsonUtil.fromJson(read().payload(), UploadResult.class);
        if (start.code() != 0) {
            return start;
        }
        String fileId = start.fileId();
        int chunk = 64 * 1024;
        for (int offset = 0; offset < data.length; offset += chunk) {
            int end = Math.min(offset + chunk, data.length);
            byte[] slice = java.util.Arrays.copyOfRange(data, offset, end);
            send(MessageType.FILE_UPLOAD_REQ, new UploadChunk(requestId, fileId, offset, end >= data.length,
                    Base64.getEncoder().encodeToString(slice)));
            if (end >= data.length) {
                return JsonUtil.fromJson(read().payload(), UploadResult.class);
            }
        }
        return start;
    }

    /** 只发上传申请（用于超限测试）。 */
    public UploadResult uploadStart(long fileSize, String fileName) throws Exception {
        send(MessageType.FILE_UPLOAD_REQ,
                new UploadStart(UUID.randomUUID().toString(), fileName, fileSize, "0".repeat(32), null));
        return JsonUtil.fromJson(read().payload(), UploadResult.class);
    }

    /** 下载：返回结果（含数据）。 */
    public DownloadResult download(String fileId, long offset) throws Exception {
        String requestId = UUID.randomUUID().toString();
        send(MessageType.FILE_DOWNLOAD_REQ, new DownloadStart(requestId, fileId, offset));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int code = 0;
        String message = "ok";
        while (true) {
            Packet packet = read();
            if (packet == null) {
                break;
            }
            DownloadChunk chunk = JsonUtil.fromJson(packet.payload(), DownloadChunk.class);
            code = chunk.code();
            message = chunk.message();
            if (code != 0) {
                break;
            }
            if (chunk.data() != null && !chunk.data().isEmpty()) {
                buffer.write(Base64.getDecoder().decode(chunk.data()));
            }
            if (chunk.eof()) {
                break;
            }
        }
        return new DownloadResult(code, message, buffer.toByteArray());
    }

    /** 下载结果。 */
    public record DownloadResult(int code, String message, byte[] data) {
    }

    private static String md5(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
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
