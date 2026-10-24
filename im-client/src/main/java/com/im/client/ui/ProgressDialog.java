package com.im.client.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 传输进度对话框：进度条 + 取消按钮（非模态，不阻塞 EDT）。
 */
public final class ProgressDialog extends JDialog {

    private final JProgressBar bar = new JProgressBar(0, 100);
    private final JLabel label = new JLabel("准备中...");
    private final AtomicBoolean cancel;

    public ProgressDialog(Window owner, String title, AtomicBoolean cancel) {
        super(owner, title, ModalityType.MODELESS);
        this.cancel = cancel;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setContentPane(buildContent());
        setSize(new Dimension(360, 130));
        setLocationRelativeTo(owner);
        setResizable(false);
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        root.add(label, BorderLayout.NORTH);
        bar.setStringPainted(true);
        root.add(bar, BorderLayout.CENTER);
        JButton cancelButton = new JButton("取消");
        cancelButton.addActionListener(e -> {
            cancel.set(true);
            label.setText("正在取消...");
            cancelButton.setEnabled(false);
        });
        root.add(cancelButton, BorderLayout.SOUTH);
        return root;
    }

    /** 由传输线程回调，内部切到 EDT。 */
    public void update(long transferred, long total) {
        int percent = total <= 0 ? 0 : (int) Math.min(100, transferred * 100 / total);
        UiThread.run(() -> {
            bar.setValue(percent);
            label.setText("已传输 " + human(transferred) + " / " + human(total) + "（" + percent + "%）");
        });
    }

    /** 关闭对话框。 */
    public void finish() {
        UiThread.run(this::dispose);
    }

    private static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
