package com.im.server.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库门面：HikariCP 连接池 + 建表。
 *
 * <p>默认 H2 文件模式（MySQL 兼容），改 {@code db.url} 即可切到 MySQL 8；
 * schema.sql 对两者通用。</p>
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    private final HikariDataSource dataSource;

    public Database(String url, String user, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, poolSize));
        config.setPoolName("im-db");
        config.setAutoCommit(true);
        this.dataSource = new HikariDataSource(config);
        initSchema();
        log.info("数据库已就绪: {}", url);
    }

    private void initSchema() {
        // H2 与 MySQL 对索引 DDL 的兼容性不同，按 JDBC URL 选择脚本
        String script = dataSource.getJdbcUrl() != null && dataSource.getJdbcUrl().startsWith("jdbc:mysql")
                ? "schema-mysql.sql" : "schema.sql";
        for (String sql : loadStatements(script)) {
            try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                throw new IllegalStateException("执行建表语句失败: " + sql, e);
            }
        }
    }

    private List<String> loadStatements(String resource) {
        List<String> statements = new ArrayList<>();
        try (InputStream in = Database.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("classpath 中缺少 " + resource);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder current = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                    continue;
                }
                current.append(line).append('\n');
                if (trimmed.endsWith(";")) {
                    String sql = current.toString().trim();
                    statements.add(sql.substring(0, sql.length() - 1));
                    current.setLength(0);
                }
            }
            if (!current.toString().isBlank()) {
                statements.add(current.toString().trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 schema.sql 失败", e);
        }
        return statements;
    }

    /** 获取连接（调用方负责关闭，或使用 try-with-resources）。 */
    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
