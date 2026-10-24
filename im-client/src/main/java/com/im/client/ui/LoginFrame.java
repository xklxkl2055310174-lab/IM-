package com.im.client.ui;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;

/**
 * 登录窗口占位（阶段 3 实现完整 Swing 界面）。
 *
 * <p>阶段 1 只保留包结构与最小可运行窗口，用于证明 ui 层独立于 net 层存在。
 * 注意：所有 Swing 操作都必须在 EDT 上执行。</p>
 */
public final class LoginFrame extends JFrame {

    public LoginFrame() {
        super("IM 客户端（阶段 1 骨架）");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(420, 260);
        setLocationRelativeTo(null);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        panel.add(new JLabel("阶段 1：工程骨架 + 通信协议"), BorderLayout.NORTH);
        panel.add(new JLabel("<html>当前使用命令行客户端验证协议：<br>"
                + "java -jar im-client.jar --user alice</html>"), BorderLayout.CENTER);
        setContentPane(panel);
    }

    /** 在 EDT 上打开窗口。 */
    public static void showWindow() {
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }
}
