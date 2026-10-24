package com.im.client;

import com.im.client.ui.LoginFrame;
import com.im.client.ui.Theme;

import javax.swing.SwingUtilities;

/**
 * 客户端入口：安装主题并显示登录窗口。
 *
 * <pre>
 * 用法: java -jar im-client.jar [--host 127.0.0.1] [--port 8080]
 * </pre>
 */
public final class ClientMain {

    private ClientMain() {
    }

    public static void main(String[] args) {
        ClientConfig base = ClientConfig.load();
        String host = base.host();
        int port = base.port();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--help", "-h" -> {
                    System.out.println("用法: java -jar im-client.jar [--host 127.0.0.1] [--port 8080]");
                    return;
                }
                default -> System.out.println("忽略未知参数: " + args[i]);
            }
        }
        ClientConfig config = new ClientConfig(host, port, base.connectTimeoutMs(),
                base.requestTimeoutMs(), base.heartbeatSeconds(), base.reconnectMaxSeconds());

        Theme.install();
        SwingUtilities.invokeLater(() -> new LoginFrame(config).setVisible(true));
    }
}
