package com.im.client.ui;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.UIManager;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 主题与字体：FlatLaf 浅色主题 + 中文字体优先。
 *
 * <p>字体顺序：Microsoft YaHei → PingFang SC → Noto Sans CJK SC → SimHei → Dialog（回退）。</p>
 */
public final class Theme {

    private static final String[] PREFERRED_FONTS = {
            "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC", "SimHei"
    };

    private Theme() {
    }

    /** 安装主题与全局字体；必须在创建任何 Swing 组件之前调用。 */
    public static void install() {
        FlatLightLaf.setup();
        Font font = uiFont();
        UIManager.put("defaultFont", font);
        UIManager.put("Label.font", font);
        UIManager.put("Button.font", font);
        UIManager.put("TextField.font", font);
        UIManager.put("PasswordField.font", font);
        UIManager.put("TextArea.font", font);
        UIManager.put("List.font", font);
        UIManager.put("TabbedPane.font", font);
        UIManager.put("OptionPane.messageFont", font);
        UIManager.put("OptionPane.buttonFont", font);
    }

    /** 选择本机可用的中文字体。 */
    public static Font uiFont() {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String name : PREFERRED_FONTS) {
            if (available.contains(name)) {
                return new Font(name, Font.PLAIN, 14);
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, 14);
    }
}
