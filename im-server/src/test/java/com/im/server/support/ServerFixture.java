package com.im.server.support;

import com.im.server.ServerConfig;
import com.im.server.ServerRuntime;
import com.im.server.db.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 测试夹具：用随机端口 + 内存 H2 + 临时文件目录启动完整服务端。
 */
public final class ServerFixture implements AutoCloseable {

    private final ServerRuntime runtime;
    private final Path files;

    private ServerFixture(ServerRuntime runtime, Path files) {
        this.runtime = runtime;
        this.files = files;
    }

    public static ServerFixture start() throws Exception {
        return start(1024 * 1024);
    }

    public static ServerFixture start(long fileMaxSize) throws Exception {
        Path files = Files.createTempDirectory("im-files");
        String dbUrl = "jdbc:h2:mem:im_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        ServerConfig config = new ServerConfig(
                0, 128, 200, 4, 256,
                0, files.toString(), fileMaxSize, 2,
                90, 1000,
                dbUrl, "sa", "", 10);
        Database database = new Database(config.dbUrl(), config.dbUser(), config.dbPassword(), config.dbPoolSize());
        ServerRuntime runtime = ServerRuntime.start(config, database);
        return new ServerFixture(runtime, files);
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

    public Path files() {
        return files;
    }

    @Override
    public void close() {
        runtime.close();
    }
}
