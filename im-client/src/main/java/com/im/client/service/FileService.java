package com.im.client.service;

import com.im.client.file.FileDtos.DownloadChunk;
import com.im.client.file.FileDtos.DownloadStart;
import com.im.client.file.FileDtos.UploadChunk;
import com.im.client.file.FileDtos.UploadResult;
import com.im.client.file.FileDtos.UploadStart;
import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.codec.PacketCodec;
import com.im.common.json.JsonUtil;
import com.im.common.message.AccountMessages.ReloginRequest;
import com.im.common.message.AccountMessages.ReloginResponse;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 文件传输服务：独立文件端口，上传/下载带进度回调，可取消，下载后校验 MD5。
 *
 * <p>传输在后台线程执行（{@link CompletableFuture} 的 fork-join 线程池，或调用方线程）；
 * UI 必须通过回调线程切到 EDT 再更新界面。</p>
 */
public final class FileService {

    /** 单块大小。 */
    private static final int CHUNK = 64 * 1024;

    /** 进度回调：已传输 / 总数。 */
    public interface Progress {
        void update(long transferred, long total);
    }

    private final String host;
    private final int filePort;
    private final String token;

    public FileService(String host, int filePort, String token) {
        this.host = host;
        this.filePort = filePort;
        this.token = token;
    }

    /** 上传文件，成功后返回 fileId。 */
    public CompletableFuture<String> upload(Path file, long toUserId, Progress progress, AtomicBoolean cancel) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] data = Files.readAllBytes(file);
                String md5 = md5(data);
                try (Socket socket = new Socket(host, filePort)) {
                    socket.setTcpNoDelay(true);
                    DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    authenticate(in, out);

                    String requestId = UUID.randomUUID().toString();
                    write(out, MessageType.FILE_UPLOAD_REQ,
                            new UploadStart(requestId, file.getFileName().toString(), data.length, md5, toUserId));
                    UploadResult start = JsonUtil.fromJson(read(in).payload(), UploadResult.class);
                    if (start.code() != ErrorCode.OK.code()) {
                        throw new IllegalStateException(start.message());
                    }
                    String fileId = start.fileId();
                    long total = data.length;
                    for (int offset = 0; offset < total; offset += CHUNK) {
                        checkCancel(cancel);
                        int end = Math.min(offset + CHUNK, data.length);
                        byte[] slice = java.util.Arrays.copyOfRange(data, offset, end);
                        write(out, MessageType.FILE_UPLOAD_REQ,
                                new UploadChunk(requestId, fileId, offset, end >= total,
                                        Base64.getEncoder().encodeToString(slice)));
                        if (progress != null) {
                            progress.update(end, total);
                        }
                        if (end >= total) {
                            UploadResult result = JsonUtil.fromJson(read(in).payload(), UploadResult.class);
                            if (result.code() != ErrorCode.OK.code()) {
                                throw new IllegalStateException(result.message());
                            }
                            return fileId;
                        }
                    }
                    throw new IllegalStateException("空文件");
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("上传失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 下载文件到目标路径并校验 MD5。
     *
     * @param expectedMd5 发送方声明的 MD5，校验失败会删除文件并失败
     */
    public CompletableFuture<Path> download(String fileId, Path target, String expectedMd5,
                                            Progress progress, AtomicBoolean cancel) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = new Socket(host, filePort)) {
                socket.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                authenticate(in, out);

                write(out, MessageType.FILE_DOWNLOAD_REQ,
                        new DownloadStart(UUID.randomUUID().toString(), fileId, 0));

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                long total = 0;
                while (true) {
                    checkCancel(cancel);
                    Packet packet = read(in);
                    if (packet == null) {
                        throw new IllegalStateException("下载连接已关闭");
                    }
                    DownloadChunk chunk = JsonUtil.fromJson(packet.payload(), DownloadChunk.class);
                    if (chunk.code() != ErrorCode.OK.code()) {
                        throw new IllegalStateException(chunk.message());
                    }
                    if (chunk.data() != null && !chunk.data().isEmpty()) {
                        byte[] slice = Base64.getDecoder().decode(chunk.data());
                        buffer.write(slice);
                        total += slice.length;
                        if (progress != null) {
                            progress.update(total, chunk.totalBytes());
                        }
                    }
                    if (chunk.eof()) {
                        break;
                    }
                }
                byte[] data = buffer.toByteArray();
                String actual = md5(data);
                if (expectedMd5 != null && !expectedMd5.isBlank()
                        && !actual.equalsIgnoreCase(expectedMd5)) {
                    throw new IllegalStateException("MD5 校验失败（文件可能损坏）");
                }
                Files.write(target, data);
                return target;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("下载失败: " + e.getMessage(), e);
            }
        });
    }

    private void authenticate(DataInputStream in, DataOutputStream out) throws Exception {
        write(out, MessageType.RELOGIN_REQ, new ReloginRequest(UUID.randomUUID().toString(), token));
        ReloginResponse response = JsonUtil.fromJson(read(in).payload(), ReloginResponse.class);
        if (response.code() != ErrorCode.OK.code()) {
            throw new IllegalStateException("文件端口认证失败: " + response.message());
        }
    }

    private static void checkCancel(AtomicBoolean cancel) {
        if (cancel != null && cancel.get()) {
            throw new java.util.concurrent.CancellationException("已取消");
        }
    }

    private static void write(DataOutputStream out, MessageType type, Object body) throws Exception {
        PacketCodec.DEFAULT.write(Packet.ofJson(type, JsonUtil.toJson(body)), out);
    }

    private static Packet read(DataInputStream in) throws Exception {
        return PacketCodec.DEFAULT.read(in);
    }

    private static String md5(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }
}
