package com.im.client.support;

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
        Path files = Files.createTempDirectory("im-client-files");
        String dbUrl = "jdbc:h2:mem:cli_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        ServerConfig config = new ServerConfig(
                0, 128, 200, 4, 256,
                0, files.toString(), 1024 * 1024, 2,
                90, 1000,
                dbUrl, "sa", "", 10);
        Database database = new Database(config.dbUrl(), config.dbUser(), config.dbPassword(), config.dbPoolSize());
        return new ServerFixture(ServerRuntime.start(config, database));
    }

    public int port() {
        return runtime.port();
    }

    public ServerRuntime runtime() {
        return runtime;
    }

    @Override
    public void close() {
        runtime.close();
    }
}
