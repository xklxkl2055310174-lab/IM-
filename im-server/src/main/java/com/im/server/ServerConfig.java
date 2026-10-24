package com.im.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 服务端可调参数（{@code server.properties}）。
 *
 * <p>总纲要求所有可调参数外置；未配置时使用默认值，保证"解压即跑"。</p>
 *
 * @param port           监听端口
 * @param backlog        连接队列长度
 * @param maxConnections 最大并发连接数，超限回 SERVER_BUSY
 * @param workerThreads  读线程池大小（0 表示按 CPU 核数 × 2 计算）
 * @param sendQueueSize  每会话发送队列容量，满则判定慢消费者并断开
 * @param fileDir        文件存储目录
 * @param filePort       文件传输端口（阶段 4 启用）
 * @param fileMaxSize    单文件大小上限（字节）
 * @param heartbeatSeconds 服务端空闲判定秒数（阶段 2 启用）
 * @param pbkdf2Iterations 密码哈希迭代次数
 */
public record ServerConfig(
        int port,
        int backlog,
        int maxConnections,
        int workerThreads,
        int sendQueueSize,
        String fileDir,
        int filePort,
        long fileMaxSize,
        int heartbeatSeconds,
        int pbkdf2Iterations) {

    /** 从 classpath 或工作目录下的 server.properties 加载。 */
    public static ServerConfig load() {
        Properties props = new Properties();
        Path file = Path.of("server.properties");
        try (InputStream in = Files.exists(file)
                ? Files.newInputStream(file)
                : ServerConfig.class.getClassLoader().getResourceAsStream("server.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            System.err.println("读取 server.properties 失败，使用默认配置: " + e.getMessage());
        }

        int cpu = Runtime.getRuntime().availableProcessors();
        return new ServerConfig(
                intOf(props, "server.port", 8080),
                intOf(props, "server.backlog", 128),
                intOf(props, "server.max-connections", 1000),
                intOf(props, "server.worker-threads", cpu * 2),
                intOf(props, "server.send-queue-size", 1024),
                props.getProperty("file.dir", "data/files"),
                intOf(props, "file.port", 8081),
                longOf(props, "file.max-size", 100L * 1024 * 1024),
                intOf(props, "server.heartbeat-seconds", 90),
                intOf(props, "security.pbkdf2-iterations", 120_000));
    }

    private static int intOf(Properties props, String key, int fallback) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static long longOf(Properties props, String key, long fallback) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
    }
}
