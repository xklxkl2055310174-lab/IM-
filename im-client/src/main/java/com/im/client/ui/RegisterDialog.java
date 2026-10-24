package com.im.client.ui;

import com.im.client.service.AuthService;
import com.im.common.message.AccountMessages.RegisterResponse;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 注册对话框：用户名/密码/确认密码，异步调用注册接口。
 */
public final class RegisterDialog extends JDialog {

    private final Supplier<AuthService> authFactory;
    private final JTextField usernameField = new JTextField(16);
    private final JPasswordField passwordField = new JPasswordField(16);
    private final JPasswordField confirmField = new JPasswordField(16);
    private final JLabel messageLabel = new JLabel(" ");
    private final JButton submitButton = new JButton("注 册");

    public RegisterDialog(java.awt.Frame owner, Supplier<AuthService> authFactory) {
        super(owner, "注册新账号", Dialog.ModalityType.APPLICATION_MODAL);
        this.authFactory = authFactory;
        setContentPane(buildContent());
        pack();
        setMinimumSize(new java.awt.Dimension(400, 300));
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(submitButton);
        submitButton.addActionListener(e -> doRegister());
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(20, 28, 20, 28));

        JLabel title = new JLabel("注册新账号");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        root.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("用户名"), c);
        c.gridx = 1;
        form.add(usernameField, c);

        c.gridx = 0;
        c.gridy = 1;
        form.add(new JLabel("密　码"), c);
        c.gridx = 1;
        form.add(passwordField, c);

        c.gridx = 0;
        c.gridy = 2;
        form.add(new JLabel("确认密码"), c);
        c.gridx = 1;
        form.add(confirmField, c);
        root.add(form, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        messageLabel.setForeground(new java.awt.Color(200, 40, 40));
        south.add(messageLabel, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 8, 0));
        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> dispose());
        buttons.add(cancel);
        buttons.add(submitButton);
        south.add(buttons, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        return root;
    }

    private void doRegister() {
        String user = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());
        String confirm = new String(confirmField.getPassword());
        if (user.isEmpty()) {
            messageLabel.setText("请输入用户名");
            return;
        }
        if (password.length() < 6) {
            messageLabel.setText("密码至少 6 位");
            return;
        }
        if (!password.equals(confirm)) {
            messageLabel.setText("两次输入的密码不一致");
            return;
        }

        submitButton.setEnabled(false);
        submitButton.setText("注册中...");
        messageLabel.setText(" ");

        CompletableFuture<RegisterResponse> future;
        try {
            future = authFactory.get().register(user, password);
        } catch (RuntimeException e) {
            submitButton.setEnabled(true);
            submitButton.setText("注 册");
            messageLabel.setText("注册失败: " + e.getMessage());
            return;
        }

        future.whenComplete((response, error) -> UiThread.run(() -> {
            submitButton.setEnabled(true);
            submitButton.setText("注 册");
            if (error != null) {
                messageLabel.setText("注册失败: " + error.getMessage());
                return;
            }
            if (response.code() != 0) {
                messageLabel.setText(response.message());
                return;
            }
            javax.swing.JOptionPane.showMessageDialog(this,
                    "注册成功，请使用新账号登录。", "提示", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            dispose();
        }));
    }
}
