package com.im.server;

import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.codec.PacketCodec;
import com.im.common.json.JsonUtil;
import com.im.common.message.SystemMessages.ServerBusy;
import com.im.server.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端运行时：主端口 + 文件端口 + 连接池的生命周期管理。
 *
 * <p>独立出来便于测试用随机端口启动与关闭。</p>
 */
public final class ServerRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ServerRuntime.class);

    private final Database database;
    private final Server server;
    private final FileServer fileServer;
    private final ServerSocket serverSocket;
    private final ThreadPoolExecutor readers;
    private final Thread acceptor;
    private final AtomicInteger online = new AtomicInteger();

    private ServerRuntime(Database database, Server server, FileServer fileServer, ServerSocket socket) {
        this.database = database;
        this.server = server;
        this.fileServer = fileServer;
        this.serverSocket = socket;
        ServerConfig config = server.config();
        AtomicInteger seq = new AtomicInteger();
        // 读线程会阻塞在 socket.read 上，因此池必须能随连接数增长到上限；
        // 用 SynchronousQueue + 大 max 实现"按需建线程、到上限即拒绝"。
        this.readers = new ThreadPoolExecutor(Math.max(2, config.workerThreads()),
                Math.max(config.workerThreads(), config.maxConnections()), 60, TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "im-reader-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.acceptor = new Thread(this::acceptLoop, "im-acceptor");
        this.acceptor.setDaemon(true);
    }

    /** 启动主端口与文件端口；{@code config.port()/filePort()} 为 0 时使用随机端口。 */
    public static ServerRuntime start(ServerConfig config, Database database) throws IOException {
        Server server = new Server(config, database);
        FileServer fileServer = new FileServer(server, database, config);
        int filePort = fileServer.start();
        ServerSocket socket = new ServerSocket(config.port(), config.backlog());
        ServerRuntime runtime = new ServerRuntime(database, server, fileServer, socket);
        runtime.acceptor.start();
        log.info("IM 服务端启动: 主端口 {}，文件端口 {}，最大连接 {}",
                socket.getLocalPort(), filePort, config.maxConnections());
        return runtime;
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                if (online.incrementAndGet() > server.config().maxConnections()) {
                    online.decrementAndGet();
                    rejectBusy(socket);
                    continue;
                }
                try {
                    Connection connection = new Connection(socket, server.config().sendQueueSize());
                    readers.execute(new ConnectionHandler(server, connection));
                } catch (IOException e) {
                    online.decrementAndGet();
                    socket.close();
                }
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    log.warn("接受连接失败: {}", e.getMessage());
                }
            }
        }
    }

    private void rejectBusy(Socket socket) {
        try (Socket s = socket) {
            var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(s.getOutputStream()));
            Packet busy = Packet.ofJson(MessageType.SERVER_BUSY,
                    JsonUtil.toJson(new ServerBusy(ErrorCode.SERVER_BUSY.code(), "服务端繁忙，请稍后重试")));
            PacketCodec.DEFAULT.write(busy, out);
        } catch (Exception e) {
            log.debug("拒绝连接出错: {}", e.getMessage());
        }
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public int filePort() {
        return fileServer.port();
    }

    public Server server() {
        return server;
    }

    public ServerConfig config() {
        return server.config();
    }

    @Override
    public void close() {
        log.info("服务端正在关闭...");
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // 忽略
        }
        fileServer.close();
        readers.shutdownNow();
        database.close();
    }
}
