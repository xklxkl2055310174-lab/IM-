package com.im.client.ui;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * 托盘通知：系统支持时弹出新消息提示，否则静默降级。
 */
public final class TrayNotifier {

    private static TrayIcon trayIcon;
    private static boolean failed;

    private TrayNotifier() {
    }

    /** 初始化托盘图标（无桌面环境时安全跳过）。 */
    public static synchronized void install() {
        if (trayIcon != null || failed) {
            return;
        }
        try {
            if (!SystemTray.isSupported()) {
                failed = true;
                return;
            }
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(new java.awt.Color(0x2E, 0x8B, 0x57));
            g.fillOval(2, 2, 12, 12);
            g.dispose();
            trayIcon = new TrayIcon(image, "IM");
            trayIcon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(trayIcon);
        } catch (Exception e) {
            failed = true;
        }
    }

    /** 弹出通知；系统不支持时忽略。 */
    public static void notify(String title, String message) {
        install();
        if (trayIcon == null) {
            return;
        }
        try {
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
