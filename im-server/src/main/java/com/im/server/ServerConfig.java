package com.im.server;

/**
 * 服务端可调参数（server.properties + 命令行覆盖）。
 *
 * @param port              主端口
 * @param backlog           连接队列长度
 * @param maxConnections    最大并发连接数
 * @param workerThreads     读线程池大小
 * @param sendQueueSize     每会话发送队列容量
 * @param filePort          文件端口
 * @param fileDir           文件存储目录
 * @param fileMaxSize       单文件大小上限（字节）
 * @param fileThreads       文件服务线程数
 * @param heartbeatSeconds  空闲判定秒数
 * @param pbkdf2Iterations  密码哈希迭代次数
 * @param dbUrl             JDBC URL（默认 H2 文件模式）
 * @param dbUser            JDBC 用户
 * @param dbPassword        JDBC 密码
 * @param dbPoolSize        连接池大小
 */
public record ServerConfig(
        int port, int backlog, int maxConnections, int workerThreads, int sendQueueSize,
        int filePort, String fileDir, long fileMaxSize, int fileThreads,
        int heartbeatSeconds, int pbkdf2Iterations,
        String dbUrl, String dbUser, String dbPassword, int dbPoolSize) {

    /** 从 classpath 或工作目录的 server.properties 加载。 */
    public static ServerConfig load() {
        java.util.Properties props = new java.util.Properties();
        java.nio.file.Path file = java.nio.file.Path.of("server.properties");
        try (java.io.InputStream in = java.nio.file.Files.exists(file)
                ? java.nio.file.Files.newInputStream(file)
                : ServerConfig.class.getClassLoader().getResourceAsStream("server.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (java.io.IOException e) {
            System.err.println("读取 server.properties 失败，使用默认配置: " + e.getMessage());
        }
        int cpu = Runtime.getRuntime().availableProcessors();
        return new ServerConfig(
                intOf(props, "server.port", 8080),
                intOf(props, "server.backlog", 128),
                intOf(props, "server.max-connections", 1000),
                intOf(props, "server.worker-threads", cpu * 2),
                intOf(props, "server.send-queue-size", 1024),
                intOf(props, "file.port", 8081),
                props.getProperty("file.dir", "data/files"),
                longOf(props, "file.max-size", 100L * 1024 * 1024),
                intOf(props, "file.threads", 4),
                intOf(props, "server.heartbeat-seconds", 90),
                intOf(props, "security.pbkdf2-iterations", 120_000),
                props.getProperty("db.url", "jdbc:h2:file:./data/im;MODE=MySQL;DB_CLOSE_DELAY=-1"),
                props.getProperty("db.user", "sa"),
                props.getProperty("db.password", ""),
                intOf(props, "db.pool-size", 10));
    }

    /** 基于当前配置覆盖端口与数据库（测试/命令行使用）。 */
    public ServerConfig with(int newPort, int newFilePort, String newDbUrl) {
        return new ServerConfig(newPort, backlog, maxConnections, workerThreads, sendQueueSize,
                newFilePort, fileDir, fileMaxSize, fileThreads, heartbeatSeconds, pbkdf2Iterations,
                newDbUrl, dbUser, dbPassword, dbPoolSize);
    }

    private static int intOf(java.util.Properties props, String key, int fallback) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static long longOf(java.util.Properties props, String key, long fallback) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
    }
}
