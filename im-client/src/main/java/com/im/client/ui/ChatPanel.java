package com.im.client.ui;

import com.im.client.service.ChatService;
import com.im.client.service.ChatService.PendingMessage;
import com.im.client.service.ChatService.Status;
import com.im.common.message.ChatMessages.PrivateMsg;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
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
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 聊天面板：消息气泡（自己靠右、对方靠左）、时间、发送状态（发送中/已发送/失败可点重发）、
 * 回车发送、Shift+回车换行、新消息自动滚到底部。
 */
public final class ChatPanel extends JPanel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Color SELF_BG = new Color(0xD9, 0xEC, 0xFF);
    private static final Color PEER_BG = new Color(0xF0, 0xF0, 0xF0);
    private static final Color GRAY = new Color(0x88, 0x88, 0x88);
    private static final Color RED = new Color(200, 40, 40);
    private static final Color GREEN = new Color(0x2E, 0x8B, 0x57);

    private final ChatService chatService;
    private final long peerId;

    private final JPanel messages = new JPanel();
    private final JScrollPane scrollPane;
    private final JTextArea input = new JTextArea(3, 20);

    public ChatPanel(long peerId, String peerName, ChatService chatService, long selfUserId) {
        super(new BorderLayout(0, 6));
        this.peerId = peerId;
        this.chatService = chatService;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel header = new JLabel("与 " + peerName + " 的会话");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 15f));
        add(header, BorderLayout.NORTH);

        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        messages.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        scrollPane = new JScrollPane(messages);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(0xDD, 0xDD, 0xDD)));
        add(scrollPane, BorderLayout.CENTER);

        add(buildInputArea(), BorderLayout.SOUTH);
    }

    private JPanel buildInputArea() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setFont(Theme.uiFont());
        input.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        // 回车发送；Shift+回车保留默认换行行为
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

        JButton sendButton = new JButton("发送");
        sendButton.addActionListener(e -> sendCurrent());
        panel.add(sendButton, BorderLayout.EAST);
        return panel;
    }

    private void sendCurrent() {
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        input.setText("");
        PendingMessage message = chatService.newMessage(peerId, text);
        appendOutgoing(message);
        chatService.send(message);
    }

    /** 渲染一条自己发送的消息，并把状态标签绑定到 PendingMessage。 */
    public void appendOutgoing(PendingMessage message) {
        JLabel status = new JLabel();
        JPanel bubble = bubble(message.content(), true, message.timestamp());
        bubble.add(status, BorderLayout.SOUTH);
        renderStatus(status, message);
        message.onStatusChanged(updated -> UiThread.run(() -> renderStatus(status, updated)));
        status.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (message.status() == Status.FAILED) {
                    chatService.resend(message);
                }
            }
        });
        addBubbleRow(bubble, true);
    }

    /** 追加一条收到的消息。 */
    public void appendIncoming(PrivateMsg msg) {
        addBubbleRow(bubble(msg.content(), false, msg.timestamp()), false);
    }

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

    private JPanel bubble(String content, boolean self, long timestamp) {
        JLabel text = new JLabel("<html><div style='width:220px'>"
                + escape(content).replace("\n", "<br>") + "</div></html>");
        text.setFont(text.getFont().deriveFont(14f));
        text.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        text.setOpaque(true);
        text.setBackground(self ? SELF_BG : PEER_BG);

        JLabel time = new JLabel(TIME.format(Instant.ofEpochMilli(timestamp)));
        time.setFont(time.getFont().deriveFont(10f));
        time.setForeground(new Color(0x99, 0x99, 0x99));

        JPanel bubble = new JPanel(new BorderLayout(0, 2));
        bubble.setOpaque(false);
        bubble.add(text, BorderLayout.CENTER);
        bubble.add(time, BorderLayout.NORTH);
        return bubble;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void addBubbleRow(JPanel bubble, boolean self) {
        JPanel row = new JPanel(new java.awt.FlowLayout(
                self ? java.awt.FlowLayout.RIGHT : java.awt.FlowLayout.LEFT, 0, 4));
        row.setOpaque(false);
        row.add(bubble);
        messages.add(row);
        messages.add(Box.createVerticalStrut(4));
        messages.revalidate();
        messages.repaint();
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    public long peerId() {
        return peerId;
    }
}
