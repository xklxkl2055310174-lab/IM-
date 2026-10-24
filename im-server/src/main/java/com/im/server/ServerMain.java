package com.im.server;

import com.im.server.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端入口。
 *
 * <pre>
 * 用法: java -jar im-server.jar [--port 8080] [--db "jdbc:h2:file:./data/im;MODE=MySQL"]
 * 其余参数写在 server.properties。
 * </pre>
 */
public final class ServerMain {

    private static final Logger log = LoggerFactory.getLogger(ServerMain.class);

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        ServerConfig base = ServerConfig.load();
        int port = base.port();
        int filePort = base.filePort();
        String dbUrl = base.dbUrl();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--file-port" -> filePort = Integer.parseInt(args[++i]);
                case "--db" -> dbUrl = args[++i];
                case "--help", "-h" -> {
                    System.out.println("用法: java -jar im-server.jar [--port 8080] [--file-port 8081] [--db jdbc:h2:...]");
                    return;
                }
                default -> log.warn("忽略未知参数: {}", args[i]);
            }
        }
        ServerConfig config = base.with(port, filePort, dbUrl);
        Database database = new Database(config.dbUrl(), config.dbUser(), config.dbPassword(), config.dbPoolSize());
        ServerRuntime runtime = ServerRuntime.start(config, database);
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "im-shutdown"));
        log.info("服务端就绪：主端口 {}，文件端口 {}", runtime.port(), runtime.filePort());
        Thread.currentThread().join();
    }
}
