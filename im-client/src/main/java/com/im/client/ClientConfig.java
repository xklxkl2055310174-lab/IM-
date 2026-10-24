package com.im.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 客户端可调参数（{@code client.properties}）。
 *
 * @param host           服务端地址
 * @param port           服务端端口
 * @param connectTimeoutMs 连接超时（毫秒）
 * @param requestTimeoutMs 请求-响应超时（毫秒）
 * @param heartbeatSeconds 心跳间隔（秒）
 * @param reconnectMaxSeconds 重连最大退避（秒）
 */
public record ClientConfig(String host, int port, int connectTimeoutMs,
                           int requestTimeoutMs, int heartbeatSeconds, int reconnectMaxSeconds) {

    /** 依次从工作目录、classpath 读取 client.properties，缺失则用默认值。 */
    public static ClientConfig load() {
        Properties props = new Properties();
        Path file = Path.of("client.properties");
        try (InputStream in = Files.exists(file)
                ? Files.newInputStream(file)
                : ClientConfig.class.getClassLoader().getResourceAsStream("client.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            System.err.println("读取 client.properties 失败，使用默认配置: " + e.getMessage());
        }
        return new ClientConfig(
                props.getProperty("client.host", "127.0.0.1"),
                intOf(props, "client.port", 8080),
                intOf(props, "client.connect-timeout-ms", 5000),
                intOf(props, "client.request-timeout-ms", 10000),
                intOf(props, "client.heartbeat-seconds", 30),
                intOf(props, "client.reconnect-max-seconds", 30));
    }

    private static int intOf(Properties props, String key, int fallback) {
        String value = props.getProperty(key);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }
}
