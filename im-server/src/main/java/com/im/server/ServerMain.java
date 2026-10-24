package com.im.server;

import com.im.common.ErrorCode;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.SystemMessages.ServerBusy;
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
 * 服务端入口。
 *
 * <p>线程模型遵循总纲：1 个 Acceptor 线程 + 有界读线程池；每连接一个写线程（在 {@link Connection} 内）。</p>
 *
 * <pre>
 * 用法: java -jar im-server.jar [--port 8080] [--data ./data]
 * 参数也可写在 server.properties 中（--port 优先级最高）。
 * </pre>
 */
public final class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig base = ServerConfig.load();
        int port = base.port();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("用法: java -jar im-server.jar [--port 8080]");
                    return;
                }
                default -> log.warn("忽略未知参数: {}", args[i]);
            }
        }
        ServerConfig config = new ServerConfig(port, base.backlog(), base.maxConnections(),
                base.workerThreads(), base.sendQueueSize(), base.fileDir(), base.filePort(),
                base.fileMaxSize(), base.heartbeatSeconds(), base.pbkdf2Iterations());

        Server server = new Server(config);

        AtomicInteger threadSeq = new AtomicInteger();
        ThreadPoolExecutor readers = new ThreadPoolExecutor(
                config.workerThreads(), config.workerThreads(), 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(64, config.maxConnections())),
                r -> {
                    Thread t = new Thread(r, "im-reader-" + threadSeq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());

        AtomicInteger online = new AtomicInteger();

        try (ServerSocket serverSocket = new ServerSocket(port, config.backlog())) {
            log.info("IM 服务端启动: 0.0.0.0:{}，最大连接 {}，读线程 {}",
                    port, config.maxConnections(), config.workerThreads());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("服务端正在关闭...");
                readers.shutdownNow();
            }));

            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);

                if (online.incrementAndGet() > config.maxConnections()) {
                    online.decrementAndGet();
                    rejectBusy(socket);
                    continue;
                }

                log.info("新连接: {}（当前 {}）", socket.getRemoteSocketAddress(), online.get());
                try {
                    Connection connection = new Connection(socket, config.sendQueueSize());
                    readers.execute(new ConnectionHandler(server, connection));
                } catch (IOException e) {
                    online.decrementAndGet();
                    log.warn("建立连接失败: {}", e.getMessage());
                    closeQuietly(socket);
                }
            }
        } finally {
            readers.shutdown();
            readers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** 超限时回 SERVER_BUSY 后关闭（总纲要求）。 */
    private static void rejectBusy(Socket socket) {
        try (Socket s = socket) {
            var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(s.getOutputStream()));
            Packet busy = Packet.ofJson(MessageType.SERVER_BUSY,
                    JsonUtil.toJson(new ServerBusy(ErrorCode.SERVER_BUSY.code(), "服务端繁忙，请稍后重试")));
            com.im.common.codec.PacketCodec.DEFAULT.write(busy, out);
        } catch (Exception e) {
            log.debug("拒绝连接时出错: {}", e.getMessage());
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 忽略
        }
    }
}
