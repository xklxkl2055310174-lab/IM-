package com.im.client.ui;

import com.im.client.file.FileMessage;
import com.im.client.service.ChatService;
import com.im.client.service.ChatService.PendingMessage;
import com.im.client.service.ChatService.Status;
import com.im.client.service.FileService;
import com.im.client.service.HistoryService;
import com.im.common.message.ChatMessages.GroupMsg;
import com.im.common.message.ChatMessages.HistoryResponse;
import com.im.common.message.ChatMessages.OfflineMsgItem;
import com.im.common.message.ChatMessages.PrivateMsg;
import com.im.common.message.GroupMessages.GroupMember;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聊天面板：私聊/群聊通用。
 *
 * <ul>
 *   <li>气泡左右区分、时间、发送状态（发送中/已发送/失败可点击重发）；</li>
 *   <li>打开会话加载最近 50 条历史，滚到顶部加载更早消息；</li>
 *   <li>群聊显示发送者昵称，右侧可放置成员列表；</li>
 *   <li>文件消息：发送文件（上传进度/取消）、下载（校验 MD5、打开所在文件夹）。</li>
 * </ul>
 */
public final class ChatPanel extends JPanel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final int HISTORY_PAGE = 50;
    private static final Color SELF_BG = new Color(0xD9, 0xEC, 0xFF);
    private static final Color PEER_BG = new Color(0xF0, 0xF0, 0xF0);
    private static final Color GRAY = new Color(0x88, 0x88, 0x88);
    private static final Color RED = new Color(200, 40, 40);
    private static final Color GREEN = new Color(0x2E, 0x8B, 0x57);

    private final long peerId;
    private final String peerName;
    private final boolean group;
    private final long selfUserId;
    private final ChatService chat;
    private final HistoryService historyService;
    private final FileService fileService;

    private final JPanel messages = new JPanel();
    private final JScrollPane scrollPane;
    private final JTextArea input = new JTextArea(3, 20);
    private final JButton sendButton = new JButton("发送");
    private final JButton fileButton = new JButton("发送文件");
    private final Map<Long, String> memberNames = new HashMap<>();
    private final Set<Long> renderedHistoryIds = new HashSet<>();
    private final Set<String> renderedMsgIds = new HashSet<>();

    private boolean loadingHistory;
    private boolean historyLoaded;
    private boolean noMoreHistory;

    public ChatPanel(long peerId, String peerName, boolean group, long selfUserId,
                     ChatService chat, HistoryService historyService, FileService fileService) {
        super(new BorderLayout(0, 6));
        this.peerId = peerId;
        this.peerName = peerName;
        this.group = group;
        this.selfUserId = selfUserId;
        this.chat = chat;
        this.historyService = historyService;
        this.fileService = fileService;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel header = new JLabel((group ? "群聊：" : "与 ") + peerName + (group ? "" : " 的会话"));
        header.setFont(header.getFont().deriveFont(Font.BOLD, 15f));
        add(header, BorderLayout.NORTH);

        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        messages.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        scrollPane = new JScrollPane(messages);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(0xDD, 0xDD, 0xDD)));
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (e.getValue() == 0 && !loadingHistory && historyLoaded && !noMoreHistory) {
                loadOlder();
            }
        });
        add(scrollPane, BorderLayout.CENTER);
        add(buildInputArea(), BorderLayout.SOUTH);

        fileButton.setEnabled(!group);
        fileButton.setToolTipText(group ? "群聊文件暂未支持" : "选择文件发送给对方");
        fileButton.addActionListener(e -> chooseAndSendFile());
    }

    /** 显示空状态提示。 */
    public void showEmptyHint(String text) {
        JLabel hint = new JLabel(text, JLabel.CENTER);
        hint.setForeground(new Color(0x99, 0x99, 0x99));
        hint.setAlignmentX(0.5f);
        messages.add(hint);
        messages.revalidate();
    }

    private JPanel buildInputArea() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setFont(Theme.uiFont());
        input.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "im-send");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                javax.swing.text.DefaultEditorKit.insertBreakAction);
        input.getActionMap().put("im-send", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendCurrent();
            }
        });

        JScrollPane inputScroll = new JScrollPane(input);
        inputScroll.setPreferredSize(new Dimension(100, 76));
        panel.add(inputScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(fileButton);
        buttons.add(sendButton);
        panel.add(buttons, BorderLayout.EAST);
        sendButton.addActionListener(e -> sendCurrent());
        return panel;
    }

    // ---------- 发送 ----------

    private void sendCurrent() {
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        input.setText("");
        PendingMessage message = chat.newMessage(peerId, text);
        appendOutgoing(message);
        if (group) {
            chat.sendGroup(peerId, text);
        } else {
            chat.send(message);
        }
    }

    private void chooseAndSendFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要发送的文件");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file == null || !file.isFile()) {
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean(false);
        ProgressDialog dialog = new ProgressDialog(SwingUtilities.getWindowAncestor(this), "上传文件", cancel);
        dialog.setVisible(true);

        fileService.upload(file.toPath(), peerId, dialog::update, cancel)
                .thenAccept(fileId -> UiThread.run(() -> {
                    dialog.finish();
                    String content = FileMessage.encode(fileId, file.getName(), file.length(), md5(file));
                    PendingMessage message = chat.newMessage(peerId, content);
                    appendOutgoing(message);
                    chat.send(message);
                }))
                .exceptionally(error -> {
                    UiThread.run(() -> {
                        dialog.finish();
                        if (error instanceof java.util.concurrent.CancellationException) {
                            showError("已取消上传");
                        } else {
                            showError("上传失败: " + rootMessage(error));
                        }
                    });
                    return null;
                });
    }

    private static String md5(File file) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("MD5").digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- 渲染 ----------

    /** 渲染一条自己发送的消息。 */
    public void appendOutgoing(PendingMessage message) {
        JLabel status = new JLabel();
        JPanel bubble = bubble(message.content(), true, message.timestamp(), null);
        bubble.add(status, BorderLayout.SOUTH);
        renderStatus(status, message);
        message.onStatusChanged(updated -> UiThread.run(() -> renderStatus(status, updated)));
        status.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (message.status() == Status.FAILED) {
                    if (group) {
                        chat.sendGroup(peerId, message.content());
                    } else {
                        chat.resend(message);
                    }
                }
            }
        });
        addBubbleRow(bubble, true);
    }

    /** 收到私聊消息。 */
    public void appendIncoming(PrivateMsg msg) {
        if (!renderedMsgIds.add(msg.msgId())) {
            return;
        }
        addBubbleRow(bubble(msg.content(), false, msg.timestamp(), null), false);
    }

    /** 收到群消息。 */
    public void appendIncomingGroup(GroupMsg msg) {
        if (!renderedMsgIds.add(msg.msgId())) {
            return;
        }
        String sender = msg.fromUserId() == selfUserId ? "我" : memberNames.getOrDefault(
                msg.fromUserId(), "用户 " + msg.fromUserId());
        addBubbleRow(bubble(msg.content(), msg.fromUserId() == selfUserId, msg.timestamp(), sender), true);
    }

    /** 渲染历史消息（按 id 正序传入）。 */
    public void appendHistory(long id, long fromUserId, String content, long timestamp) {
        if (!renderedHistoryIds.add(id)) {
            return;
        }
        boolean self = fromUserId == selfUserId;
        String sender = group && !self ? memberNames.getOrDefault(fromUserId, "用户 " + fromUserId) : null;
        addBubbleRow(bubble(content, self, timestamp, sender), group);
    }

    /** 更新群成员名称映射（用于历史与群消息显示昵称）。 */
    public void updateMembers(java.util.List<GroupMember> members) {
        memberNames.clear();
        for (GroupMember member : members) {
            memberNames.put(member.userId(), member.username());
        }
    }

    // ---------- 历史 ----------

    /** 打开会话时加载最近 50 条。 */
    public void loadInitialHistory() {
        if (historyLoaded) {
            return;
        }
        historyLoaded = true;
        loadingHistory = true;
        historyService.history(peerId, group ? 2 : 1, 0, HISTORY_PAGE).whenComplete((response, error) ->
                UiThread.run(() -> {
                    loadingHistory = false;
                    if (error != null || response == null || response.code() != 0) {
                        return;
                    }
                    HistoryResponse typed = response;
                    java.util.List<OfflineMsgItem> items = typed.messages();
                    if (items.isEmpty()) {
                        return;
                    }
                    // 接口按 id 倒序返回，这里按正序渲染
                    for (int i = items.size() - 1; i >= 0; i--) {
                        OfflineMsgItem item = items.get(i);
                        appendHistory(item.id(), item.fromUserId(), item.content(), item.timestamp());
                    }
                    if (items.size() < HISTORY_PAGE) {
                        noMoreHistory = true;
                    }
                    scrollToBottom();
                }));
    }

    private void loadOlder() {
        long oldest = renderedHistoryIds.stream().mapToLong(Long::longValue).min().orElse(0L);
        if (oldest <= 0) {
            noMoreHistory = true;
            return;
        }
        loadingHistory = true;
        int beforeHeight = messages.getPreferredSize().height;
        historyService.history(peerId, group ? 2 : 1, oldest, HISTORY_PAGE).whenComplete((response, error) ->
                UiThread.run(() -> {
                    loadingHistory = false;
                    if (error != null || response == null || response.code() != 0) {
                        return;
                    }
                    java.util.List<OfflineMsgItem> items = response.messages();
                    if (items.isEmpty()) {
                        noMoreHistory = true;
                        return;
                    }
                    // 更早消息插到顶部：按正序逐个插入 0 位置
                    for (int i = 0; i < items.size(); i++) {
                        OfflineMsgItem item = items.get(i);
                        if (!renderedHistoryIds.add(item.id())) {
                            continue;
                        }
                        boolean self = item.fromUserId() == selfUserId;
                        String sender = group && !self
                                ? memberNames.getOrDefault(item.fromUserId(), "用户 " + item.fromUserId()) : null;
                        JPanel bubble = bubble(item.content(), self, item.timestamp(), sender);
                        JPanel row = rowFor(bubble, group);
                        messages.add(row, 0);
                        messages.add(Box.createVerticalStrut(4), 1);
                    }
                    messages.revalidate();
                    int afterHeight = messages.getPreferredSize().height;
                    JScrollBar bar = scrollPane.getVerticalScrollBar();
                    bar.setValue(afterHeight - beforeHeight);
                }));
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    // ---------- 气泡 ----------

    private void renderStatus(JLabel label, PendingMessage message) {
        label.setFont(label.getFont().deriveFont(11f));
        switch (message.status()) {
            case SENDING -> {
                label.setForeground(GRAY);
                label.setText("发送中...");
                label.setCursor(java.awt.Cursor.getDefaultCursor());
            }
            case SENT -> {
                label.setForeground(GREEN);
                label.setText("已发送");
                label.setCursor(java.awt.Cursor.getDefaultCursor());
            }
            case FAILED -> {
                label.setForeground(RED);
                label.setText("发送失败，点击重发");
                label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            }
        }
    }

    private JPanel bubble(String content, boolean self, long timestamp, String senderName) {
        FileMessage.Ref fileRef = FileMessage.parse(content);
        JPanel body;
        if (fileRef != null) {
            body = fileBody(fileRef, self);
        } else {
            JLabel text = new JLabel("<html><div style='width:220px'>"
                    + escape(content).replace("\n", "<br>") + "</div></html>");
            text.setFont(text.getFont().deriveFont(14f));
            text.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            text.setOpaque(true);
            text.setBackground(self ? SELF_BG : PEER_BG);
            body = new JPanel(new BorderLayout());
            body.setOpaque(false);
            body.add(text, BorderLayout.CENTER);
        }

        JPanel bubble = new JPanel(new BorderLayout(0, 2));
        bubble.setOpaque(false);
        if (senderName != null && !self) {
            JLabel sender = new JLabel(senderName);
            sender.setFont(sender.getFont().deriveFont(Font.BOLD, 11f));
            sender.setForeground(new Color(0x66, 0x66, 0x66));
            bubble.add(sender, BorderLayout.NORTH);
        }
        JLabel time = new JLabel(TIME.format(Instant.ofEpochMilli(timestamp)));
        time.setFont(time.getFont().deriveFont(10f));
        time.setForeground(new Color(0x99, 0x99, 0x99));
        JPanel wrapper = new JPanel(new BorderLayout(0, 2));
        wrapper.setOpaque(false);
        wrapper.add(body, BorderLayout.CENTER);
        wrapper.add(time, BorderLayout.SOUTH);
        bubble.add(wrapper, BorderLayout.CENTER);
        return bubble;
    }

    /** 文件气泡：文件名 + 大小 + 下载按钮。 */
    private JPanel fileBody(FileMessage.Ref ref, boolean self) {
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        panel.setOpaque(true);
        panel.setBackground(self ? SELF_BG : PEER_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JLabel name = new JLabel("📎 " + ref.fileName() + "（" + humanSize(ref.size()) + "）");
        panel.add(name, BorderLayout.CENTER);
        JButton download = new JButton("下载");
        download.addActionListener(e -> downloadFile(ref));
        panel.add(download, BorderLayout.SOUTH);
        return panel;
    }

    private void downloadFile(FileMessage.Ref ref) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存文件");
        chooser.setSelectedFile(new File(ref.fileName()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        AtomicBoolean cancel = new AtomicBoolean(false);
        ProgressDialog dialog = new ProgressDialog(SwingUtilities.getWindowAncestor(this), "下载文件", cancel);
        dialog.setVisible(true);

        fileService.download(ref.fileId(), target.toPath(), ref.md5(), dialog::update, cancel)
                .thenAccept(path -> UiThread.run(() -> {
                    dialog.finish();
                    int choice = javax.swing.JOptionPane.showConfirmDialog(this,
                            "下载完成，MD5 校验通过：\n" + path + "\n\n是否打开所在文件夹？",
                            "下载完成", javax.swing.JOptionPane.YES_NO_OPTION);
                    if (choice == javax.swing.JOptionPane.YES_OPTION) {
                        openFolder(path.getParent());
                    }
                }))
                .exceptionally(error -> {
                    UiThread.run(() -> {
                        dialog.finish();
                        showError(error instanceof java.util.concurrent.CancellationException
                                ? "已取消下载" : "下载失败: " + rootMessage(error));
                    });
                    return null;
                });
    }

    private void openFolder(Path folder) {
        try {
            java.awt.Desktop.getDesktop().open(folder == null ? Path.of(".").toFile() : folder.toFile());
        } catch (Exception e) {
            showError("无法打开文件夹: " + e.getMessage());
        }
    }

    private void showError(String message) {
        javax.swing.JOptionPane.showMessageDialog(this, message, "提示", javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JPanel rowFor(JPanel bubble, boolean self) {
        JPanel row = new JPanel(new FlowLayout(self ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 4));
        row.setOpaque(false);
        row.add(bubble);
        return row;
    }

    private void addBubbleRow(JPanel bubble, boolean self) {
        messages.add(rowFor(bubble, self));
        messages.add(Box.createVerticalStrut(4));
        messages.revalidate();
        messages.repaint();
        scrollToBottom();
    }

    public long peerId() {
        return peerId;
    }

    public boolean isGroup() {
        return group;
    }
}
