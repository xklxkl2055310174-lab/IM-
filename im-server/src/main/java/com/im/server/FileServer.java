package com.im.server;

import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.ProtocolException;
import com.im.common.codec.PacketCodec;
import com.im.common.json.JsonUtil;
import com.im.common.message.AccountMessages.ReloginRequest;
import com.im.common.message.AccountMessages.ReloginResponse;
import com.im.common.message.SystemMessages.ErrorMessage;
import com.im.server.db.Database;
import com.im.server.file.FileDto;
import com.im.server.file.FileDto.DownloadChunk;
import com.im.server.file.FileDto.DownloadStart;
import com.im.server.file.FileDto.UploadChunk;
import com.im.server.file.FileDto.UploadResult;
import com.im.server.file.FileDto.UploadStart;
import com.im.server.repo.FileRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文件服务：独立端口 + 独立线程池。
 *
 * <p>安全与正确性要点：</p>
 * <ul>
 *   <li>连接必须先用 {@code RELOGIN_REQ} 携带有效 token 认证；</li>
 *   <li>上传流式写临时文件并边写边算 MD5，校验通过才重命名为 fileId 并写 file_meta；</li>
 *   <li>下载仅允许上传者与接收方；支持 offset 续传；</li>
 *   <li>大小上限与存储目录由配置决定，原文件名只入库存（防路径穿越）。</li>
 * </ul>
 */
public final class FileServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileServer.class);

    /** 单次下载分块大小。 */
    public static final int CHUNK = 64 * 1024;

    private final Server server;
    private final Database database;
    private final FileRepo files;
    private final ServerConfig config;
    private final Path root;
    private final ExecutorService pool;
    private final Map<String, UploadState> uploads = new ConcurrentHashMap<>();
    private volatile ServerSocket serverSocket;

    /** 上传中的临时状态。 */
    private static final class UploadState {
        Path temp;
        OutputStream out;
        MessageDigest md5;
        long written;
        long expectedSize;
        String expectedMd5;
        String fileName;
        Long receiverId;
    }

    public FileServer(Server server, Database database, ServerConfig config) throws IOException {
        this.server = server;
        this.database = database;
        this.files = new FileRepo(database);
        this.config = config;
        this.root = Path.of(config.fileDir());
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("tmp"));
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(Math.max(2, config.fileThreads()), r -> {
            Thread t = new Thread(r, "im-file-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** 绑定并开始接受连接，返回实际监听端口。 */
    public int start() throws IOException {
        ServerSocket socket = new ServerSocket(config.filePort());
        this.serverSocket = socket;
        Thread acceptor = new Thread(() -> acceptLoop(socket), "im-file-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("文件服务启动: 0.0.0.0:{}，存储目录 {}", socket.getLocalPort(), root.toAbsolutePath());
        return socket.getLocalPort();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                client.setTcpNoDelay(true);
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    log.warn("文件端口接受连接失败: {}", e.getMessage());
                }
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // 第一步：token 认证
            Packet first = PacketCodec.DEFAULT.read(in);
            if (first == null || first.type() != MessageType.RELOGIN_REQ) {
                send(out, error("", com.im.common.ErrorCode.NOT_LOGGED_IN, "文件端口需先重登认证"));
                return;
            }
            ReloginRequest auth = JsonUtil.fromJson(first.payload(), ReloginRequest.class);
            Long userId = server.userIdByToken(auth.token());
            if (userId == null) {
                send(out, json(MessageType.RELOGIN_RESP, new ReloginResponse(auth.requestId(),
                        com.im.common.ErrorCode.NOT_LOGGED_IN.code(), "token 无效", 0, "")));
                return;
            }
            send(out, json(MessageType.RELOGIN_RESP, new ReloginResponse(auth.requestId(),
                    com.im.common.ErrorCode.OK.code(), "认证成功", userId, server.usernameOf(userId))));

            while (true) {
                Packet packet = PacketCodec.DEFAULT.read(in);
                if (packet == null) {
                    break;
                }
                if (packet.type() == MessageType.FILE_UPLOAD_REQ) {
                    handleUpload(userId, packet, out);
                } else if (packet.type() == MessageType.FILE_DOWNLOAD_REQ) {
                    handleDownload(userId, packet, out);
                } else {
                    send(out, error("", com.im.common.ErrorCode.INVALID_PAYLOAD, "文件端口不支持该消息"));
                }
            }
        } catch (ProtocolException e) {
            log.warn("文件端口协议错误: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("文件连接异常: {}", e.getMessage());
        }
    }

    // ---------- 上传 ----------

    private void handleUpload(long userId, Packet packet, DataOutputStream out) throws Exception {
        String json = packet.payloadAsString();
        boolean isChunk = json.contains("\"fileId\"");
        if (isChunk) {
            UploadChunk chunk = JsonUtil.fromJson(packet.payload(), UploadChunk.class);
            UploadState state = uploads.get(chunk.fileId());
            if (state == null) {
                send(out, uploadResult(chunk.requestId(), com.im.common.ErrorCode.FILE_NOT_FOUND, "上传会话不存在", chunk.fileId(), 0));
                return;
            }
            if (chunk.offset() != state.written) {
                send(out, uploadResult(chunk.requestId(), com.im.common.ErrorCode.TRANSFER_FAILED,
                        "偏移不连续：期望 " + state.written + "，实际 " + chunk.offset(), chunk.fileId(), state.written));
                return;
            }
            byte[] data = chunk.data() == null ? new byte[0] : Base64.getDecoder().decode(chunk.data());
            state.out.write(data);
            state.md5.update(data);
            state.written += data.length;

            if (!chunk.eof()) {
                return;
            }
            state.out.close();
            String actual = HexFormat.of().formatHex(state.md5.digest());
            if (state.written != state.expectedSize || !actual.equalsIgnoreCase(state.expectedMd5)) {
                Files.deleteIfExists(state.temp);
                uploads.remove(chunk.fileId());
                send(out, uploadResult(chunk.requestId(), com.im.common.ErrorCode.TRANSFER_FAILED,
                        "MD5 或大小校验失败", chunk.fileId(), state.written));
                return;
            }
            Path target = root.resolve(chunk.fileId());
            Files.move(state.temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            files.insert(new FileRepo.FileMeta(chunk.fileId(), state.fileName, state.written, actual,
                    userId, state.receiverId, System.currentTimeMillis()));
            uploads.remove(chunk.fileId());
            log.info("文件上传完成: {} ({} 字节, md5={})", chunk.fileId(), state.written, actual);
            send(out, uploadResult(chunk.requestId(), com.im.common.ErrorCode.OK, "上传完成", chunk.fileId(), state.written));
            return;
        }

        UploadStart start = JsonUtil.fromJson(packet.payload(), UploadStart.class);
        if (start.fileSize() <= 0 || start.fileSize() > config.fileMaxSize()) {
            send(out, uploadResult(start.requestId(), com.im.common.ErrorCode.FILE_TOO_LARGE,
                    "文件超过上限 " + config.fileMaxSize(), "", 0));
            return;
        }
        String fileId = UUID.randomUUID().toString();
        UploadState state = new UploadState();
        state.temp = root.resolve("tmp").resolve(fileId + ".part");
        state.out = Files.newOutputStream(state.temp);
        state.md5 = MessageDigest.getInstance("MD5");
        state.expectedSize = start.fileSize();
        state.expectedMd5 = start.md5();
        state.fileName = start.fileName() == null ? "unnamed" : start.fileName();
        state.receiverId = start.toUserId();
        uploads.put(fileId, state);
        log.info("开始上传: {} ({} 字节) by {}", state.fileName, state.expectedSize, userId);
        send(out, uploadResult(start.requestId(), com.im.common.ErrorCode.OK, "可以开始上传", fileId, 0));
    }

    // ---------- 下载 ----------

    private void handleDownload(long userId, Packet packet, DataOutputStream out) throws Exception {
        DownloadStart start = JsonUtil.fromJson(packet.payload(), DownloadStart.class);
        FileRepo.FileMeta meta = files.find(start.fileId()).orElse(null);
        if (meta == null) {
            send(out, downloadChunk(start.requestId(), com.im.common.ErrorCode.FILE_NOT_FOUND,
                    "文件不存在", start.fileId(), "", 0, 0, true, ""));
            return;
        }
        boolean allowed = meta.uploaderId() == userId
                || (meta.receiverId() != null && meta.receiverId() == userId);
        if (!allowed) {
            send(out, downloadChunk(start.requestId(), com.im.common.ErrorCode.NO_PERMISSION,
                    "无权下载该文件", start.fileId(), meta.fileName(), meta.size(), 0, true, ""));
            return;
        }
        Path path = root.resolve(meta.fileId());
        if (!Files.exists(path)) {
            send(out, downloadChunk(start.requestId(), com.im.common.ErrorCode.FILE_NOT_FOUND,
                    "文件已丢失", start.fileId(), meta.fileName(), 0, 0, true, ""));
            return;
        }
        long offset = Math.max(0, start.offset());
        try (InputStream raw = Files.newInputStream(path)) {
            DigestInputStream in = new DigestInputStream(raw, MessageDigest.getInstance("MD5"));
            in.skipNBytes(offset);
            byte[] buffer = new byte[CHUNK];
            while (true) {
                int read = in.read(buffer);
                if (read <= 0) {
                    break;
                }
                byte[] slice = Arrays.copyOf(buffer, read);
                long next = offset + read;
                boolean eof = next >= meta.size();
                send(out, downloadChunk(start.requestId(), com.im.common.ErrorCode.OK, "分块",
                        meta.fileId(), meta.fileName(), meta.size(), (int) offset, eof,
                        Base64.getEncoder().encodeToString(slice)));
                offset = next;
                if (eof) {
                    break;
                }
            }
        }
    }

    // ---------- 工具 ----------

    private Packet uploadResult(String requestId, com.im.common.ErrorCode code, String message,
                                String fileId, long uploadedBytes) {
        return json(MessageType.FILE_UPLOAD_RESP, new UploadResult(requestId, code.code(), message, fileId, uploadedBytes));
    }

    private Packet downloadChunk(String requestId, com.im.common.ErrorCode code, String message, String fileId,
                                 String fileName, long total, int offset, boolean eof, String data) {
        return json(MessageType.FILE_DOWNLOAD_RESP,
                new DownloadChunk(requestId, code.code(), message, fileId, fileName, total, offset, eof, data));
    }

    private Packet json(MessageType type, Object body) {
        return Packet.ofJson(type, JsonUtil.toJson(body));
    }

    private Packet error(String requestId, com.im.common.ErrorCode code, String message) {
        return json(MessageType.ERROR, new ErrorMessage(requestId, code.code(), message));
    }

    private void send(DataOutputStream out, Packet packet) throws IOException, ProtocolException {
        PacketCodec.DEFAULT.write(packet, out);
    }

    /** 当前监听端口。 */
    public int port() {
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    @Override
    public void close() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // 忽略
        }
        pool.shutdownNow();
        uploads.values().forEach(state -> {
            try {
                state.out.close();
                Files.deleteIfExists(state.temp);
            } catch (IOException ignored) {
                // 忽略
            }
        });
        uploads.clear();
    }
}
