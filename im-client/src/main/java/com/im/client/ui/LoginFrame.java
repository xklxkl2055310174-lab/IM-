package com.im.client.ui;

import com.im.client.ClientConfig;
import com.im.client.net.ImConnection;
import com.im.client.service.AuthService;
import com.im.common.message.AccountMessages.LoginResponse;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.Preferences;

/**
 * 登录窗口：表单校验、登录中禁用按钮、错误提示、记住用户名。
 *
 * <p>所有网络调用都在后台线程（CompletableFuture），UI 更新统一走 {@link UiThread}。</p>
 */
public final class LoginFrame extends JFrame {

    private static final String PREF_USER = "im.username";
    private static final Preferences PREFS = Preferences.userNodeForPackage(LoginFrame.class);

    private final ClientConfig config;
    private final JTextField usernameField = new JTextField(16);
    private final JPasswordField passwordField = new JPasswordField(16);
    private final JCheckBox rememberBox = new JCheckBox("记住用户名", true);
    private final JLabel errorLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JButton loginButton = new JButton("登 录");
    private final JButton registerButton = new JButton("注册新账号");

    public LoginFrame(ClientConfig config) {
        super("IM 登录");
        this.config = config;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setContentPane(buildContent());
        pack();
        setMinimumSize(new Dimension(420, 340));
        setLocationRelativeTo(null);

        String remembered = PREFS.get(PREF_USER, "");
        if (!remembered.isBlank()) {
            usernameField.setText(remembered);
            passwordField.requestFocusInWindow();
        }

        loginButton.addActionListener(e -> doLogin());
        registerButton.addActionListener(e -> new RegisterDialog(this, this::createAuthService).setVisible(true));
        getRootPane().setDefaultButton(loginButton);
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));

        JLabel title = new JLabel("IM 即时通讯", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        root.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

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
        c.gridwidth = 2;
        form.add(rememberBox, c);

        root.add(form, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 10));
        errorLabel.setForeground(new java.awt.Color(200, 40, 40));
        south.add(errorLabel, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 10, 0));
        buttons.add(registerButton);
        buttons.add(loginButton);
        south.add(buttons, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        return root;
    }

    /** 创建（或复用）一个已连接的 AuthService。 */
    private AuthService createAuthService() {
        ImConnection connection = new ImConnection(Duration.ofSeconds(10), Duration.ofSeconds(30), 3);
        try {
            connection.connect(config.host(), config.port(), Duration.ofMillis(config.connectTimeoutMs()));
        } catch (IOException e) {
            throw new IllegalStateException("无法连接服务器: " + e.getMessage(), e);
        }
        return new AuthService(connection);
    }

    private void doLogin() {
        String user = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());
        if (user.isEmpty()) {
            showError("请输入用户名");
            return;
        }
        if (password.isEmpty()) {
            showError("请输入密码");
            return;
        }
        setBusy(true);
        showError(" ");

        AuthService auth;
        try {
            auth = createAuthService();
        } catch (RuntimeException e) {
            setBusy(false);
            showError(e.getMessage());
            return;
        }

        CompletableFuture<LoginResponse> future = auth.login(user, password);
        future.whenComplete((response, error) -> UiThread.run(() -> {
            if (error != null) {
                setBusy(false);
                showError("登录失败: " + rootMessage(error));
                return;
            }
            if (response.code() != 0) {
                setBusy(false);
                showError(response.message());
                return;
            }
            if (rememberBox.isSelected()) {
                PREFS.put(PREF_USER, user);
            } else {
                PREFS.remove(PREF_USER);
            }
            dispose();
            new MainFrame(config, auth, auth.session()).setVisible(true);
        }));
    }

    private void setBusy(boolean busy) {
        loginButton.setEnabled(!busy);
        registerButton.setEnabled(!busy);
        usernameField.setEnabled(!busy);
        passwordField.setEnabled(!busy);
        loginButton.setText(busy ? "登录中..." : "登 录");
    }

    private void showError(String message) {
        errorLabel.setText(message);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
