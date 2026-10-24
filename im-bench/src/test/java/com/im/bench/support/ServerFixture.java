package com.im.bench.support;

import com.im.server.ServerConfig;
import com.im.server.ServerRuntime;
import com.im.server.db.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 测试夹具：随机端口 + 内存 H2 + 临时文件目录启动真实服务端。
 */
public final class ServerFixture implements AutoCloseable {

    private final ServerRuntime runtime;

    private ServerFixture(ServerRuntime runtime) {
        this.runtime = runtime;
    }

    public static ServerFixture start() throws Exception {
        return start(0, 0);
    }

    public static ServerFixture start(int port, int filePort) throws Exception {
        Path files = Files.createTempDirectory("im-bench-files");
        String dbUrl = "jdbc:h2:mem:bench_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        ServerConfig config = new ServerConfig(
                port, 128, 2000, 8, 1024,
                filePort, files.toString(), 16 * 1024 * 1024, 4,
                90, 120_000,
                dbUrl, "sa", "", 20);
        Database database = new Database(config.dbUrl(), config.dbUser(), config.dbPassword(), config.dbPoolSize());
        return new ServerFixture(ServerRuntime.start(config, database));
    }

    public int port() {
        return runtime.port();
    }

    public int filePort() {
        return runtime.filePort();
    }

    public ServerRuntime runtime() {
        return runtime;
    }

    @Override
    public void close() {
        runtime.close();
    }
}
