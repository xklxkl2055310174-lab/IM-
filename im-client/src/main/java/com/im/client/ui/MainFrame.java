package com.im.client.ui;

import com.im.client.ClientConfig;
import com.im.client.net.ImConnection;
import com.im.client.service.AuthService;
import com.im.client.service.AuthService.Session;
import com.im.client.service.ChatService;
import com.im.client.service.ContactService;
import com.im.common.MessageType;
import com.im.common.json.JsonUtil;
import com.im.common.message.ChatMessages.PrivateMsg;
import com.im.common.message.ContactMessages.OnlineUser;
import com.im.common.message.ContactMessages.OnlineUsersResponse;
import com.im.common.message.SystemMessages.KickNotify;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * 主窗口：左侧「会话 / 在线用户」标签页，右侧 CardLayout 聊天面板，底部连接状态，标题显示当前用户。
 */
public final class MainFrame extends JFrame {

    private static final String CARD_EMPTY = "__empty__";

    private final ClientConfig config;
    private final AuthService auth;
    private final ImConnection connection;
    private final ChatService chat;
    private final ContactService contacts;
    private final Session session;

    private final Map<Long, Conversation> conversationByPeer = new HashMap<>();
    private final Map<Long, ChatPanel> panelByPeer = new HashMap<>();
    private final DefaultListModel<Conversation> conversationModel = new DefaultListModel<>();
    private final DefaultListModel<OnlineUser> onlineModel = new DefaultListModel<>();
    private final JList<Conversation> conversationList = new JList<>(conversationModel);
    private final JList<OnlineUser> onlineList = new JList<>(onlineModel);
    private final JPanel cards = new JPanel(new CardLayout());
    private final JLabel statusLabel = new JLabel("连接状态：已连接");
    private final JTabbedPane tabs = new JTabbedPane();

    public MainFrame(ClientConfig config, AuthService auth, Session session) {
        super("IM - " + session.username() + " (" + session.userId() + ")");
        this.config = config;
        this.auth = auth;
        this.session = session;
        this.connection = auth.connection();
        this.chat = new ChatService(connection, session.userId());
        this.contacts = new ContactService(connection);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(860, 560));
        setContentPane(buildContent());
        setLocationRelativeTo(null);

        wireEvents();
        refreshOnlineUsers();
    }

    // ---------- 布局 ----------

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 0));

        conversationList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        conversationList.setFont(Theme.uiFont());
        conversationList.setCellRenderer(new ConversationRenderer());
        onlineList.setFont(Theme.uiFont());
        onlineList.setCellRenderer(new OnlineRenderer());

        tabs.addTab("会话", new JScrollPane(conversationList));
        tabs.addTab("在线用户", new JScrollPane(onlineList));
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedIndex() == 1) {
                refreshOnlineUsers();
            }
        });

        JPanel left = new JPanel(new BorderLayout());
        left.setPreferredSize(new Dimension(220, 0));
        left.add(tabs, BorderLayout.CENTER);
        root.add(left, BorderLayout.WEST);

        JPanel empty = new JPanel(new BorderLayout());
        JLabel hint = new JLabel("从左侧选择或双击在线用户开始聊天", JLabel.CENTER);
        hint.setForeground(new Color(0x99, 0x99, 0x99));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 15f));
        empty.add(hint, BorderLayout.CENTER);
        cards.add(empty, CARD_EMPTY);
        root.add(cards, BorderLayout.CENTER);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        statusLabel.setFont(statusLabel.getFont().deriveFont(12f));
        statusLabel.setForeground(new Color(0x55, 0x55, 0x55));
        root.add(statusLabel, BorderLayout.SOUTH);
        return root;
    }

    private void wireEvents() {
        conversationList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            Conversation selected = conversationList.getSelectedValue();
            if (selected != null) {
                openConversation(selected);
            }
        });

        onlineList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    OnlineUser user = onlineList.getSelectedValue();
                    if (user != null && user.userId() != session.userId()) {
                        openConversation(conversationFor(user.userId(), user.username()));
                    }
                }
            }
        });

        chat.onIncoming(this::onIncoming);
        connection.on(MessageType.KICK_NOTIFY, packet -> UiThread.run(() -> onKicked(packet)));
        connection.onState(state -> UiThread.run(() -> statusLabel.setText("连接状态：" + stateText(state))));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
    }

    // ---------- 会话 ----------

    private Conversation conversationFor(long peerId, String name) {
        return conversationByPeer.computeIfAbsent(peerId, id -> {
            Conversation conversation = new Conversation(id, name == null ? "用户 " + id : name);
            conversationModel.addElement(conversation);
            return conversation;
        });
    }

    private void openConversation(Conversation conversation) {
        ChatPanel panel = panelByPeer.get(conversation.peerId());
        if (panel == null) {
            panel = new ChatPanel(conversation.peerId(), conversation.name(), chat, session.userId());
            panelByPeer.put(conversation.peerId(), panel);
            cards.add(panel, String.valueOf(conversation.peerId()));
        }
        ((CardLayout) cards.getLayout()).show(cards, String.valueOf(conversation.peerId()));
        conversation.setUnread(0);
        conversationList.repaint();
    }

    private void onIncoming(PrivateMsg message) {
        UiThread.run(() -> {
            Conversation conversation = conversationFor(message.fromUserId(), null);
            openOrNotify(conversation, message);
        });
    }

    private void openOrNotify(Conversation conversation, PrivateMsg message) {
        ChatPanel panel = panelByPeer.get(conversation.peerId());
        boolean isCurrent = panel != null && conversationList.getSelectedValue() == conversation;
        if (panel == null) {
            panel = new ChatPanel(conversation.peerId(), conversation.name(), chat, session.userId());
            panelByPeer.put(conversation.peerId(), panel);
            cards.add(panel, String.valueOf(conversation.peerId()));
        }
        panel.appendIncoming(message);
        if (!isCurrent) {
            conversation.incrementUnread();
            conversationList.repaint();
            setTitle("IM - " + session.username() + " (" + session.userId() + ")  ● 新消息");
        }
    }

    // ---------- 在线用户 ----------

    private void refreshOnlineUsers() {
        contacts.onlineUsers().whenComplete((response, error) -> UiThread.run(() -> {
            onlineModel.clear();
            if (error != null || response == null) {
                return;
            }
            OnlineUsersResponse typed = response;
            if (typed.code() != 0) {
                return;
            }
            for (OnlineUser user : typed.users()) {
                onlineModel.addElement(user);
                Conversation conversation = conversationByPeer.get(user.userId());
                if (conversation != null) {
                    conversation.setName(user.username());
                    conversation.setOnline(true);
                    conversationList.repaint();
                }
            }
            conversationByPeer.values().forEach(c -> {
                if (onlineModel.getSize() > 0 && findOnline(c.peerId()) == null) {
                    c.setOnline(false);
                }
            });
            conversationList.repaint();
        }));
    }

    private OnlineUser findOnline(long userId) {
        for (int i = 0; i < onlineModel.size(); i++) {
            if (onlineModel.get(i).userId() == userId) {
                return onlineModel.get(i);
            }
        }
        return null;
    }

    // ---------- 事件 ----------

    private void onKicked(com.im.common.Packet packet) {
        String message = "账号已在别处登录";
        try {
            KickNotify kick = JsonUtil.fromJson(packet.payload(), KickNotify.class);
            message = kick.message();
        } catch (Exception ignored) {
            // 用默认文案
        }
        JOptionPane.showMessageDialog(this, message, "已下线", JOptionPane.WARNING_MESSAGE);
        chat.close();
        connection.close();
        dispose();
        new LoginFrame(config).setVisible(true);
    }

    private void shutdown() {
        try {
            auth.logout();
        } catch (RuntimeException ignored) {
            // 忽略
        }
        chat.close();
        connection.close();
        dispose();
        new LoginFrame(config).setVisible(true);
    }

    private static String stateText(ImConnection.State state) {
        return switch (state) {
            case DISCONNECTED -> "已断开";
            case CONNECTING -> "连接中";
            case CONNECTED -> "已连接";
            case AUTHENTICATED -> "已认证";
        };
    }

    /** 会话条目。 */
    public static final class Conversation {
        private final long peerId;
        private String name;
        private int unread;
        private boolean online;

        Conversation(long peerId, String name) {
            this.peerId = peerId;
            this.name = name;
        }

        public long peerId() {
            return peerId;
        }

        public String name() {
            return name;
        }

        void setName(String name) {
            this.name = name;
        }

        void incrementUnread() {
            unread++;
        }

        void setUnread(int unread) {
            this.unread = unread;
        }

        public int unread() {
            return unread;
        }

        public boolean online() {
            return online;
        }

        void setOnline(boolean online) {
            this.online = online;
        }

        @Override
        public String toString() {
            return unread > 0 ? name + "  (" + unread + ")" : name;
        }
    }

    /** 小圆点图标：表示在线/离线。 */
    private static final class DotIcon implements javax.swing.Icon {
        private static final int SIZE = 9;
        private final Color color;

        DotIcon(Color color) {
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return SIZE + 6;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x + 3, y, SIZE, SIZE);
            g2.dispose();
        }
    }

    /** 会话列表渲染：在线圆点 + 名称 + 未读数。 */
    private static final class ConversationRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focus) {
            javax.swing.JLabel label = (javax.swing.JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            if (value instanceof Conversation conversation) {
                label.setText(conversation.name() + (conversation.unread() > 0
                        ? "  [" + conversation.unread() + "]" : ""));
                label.setIcon(new DotIcon(conversation.online()
                        ? new Color(0x2E, 0x8B, 0x57) : new Color(0xBB, 0xBB, 0xBB)));
            }
            return label;
        }
    }

    /** 在线用户渲染：绿色圆点 + 用户名。 */
    private static final class OnlineRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focus) {
            javax.swing.JLabel label = (javax.swing.JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            if (value instanceof OnlineUser user) {
                label.setText(user.username());
                label.setIcon(new DotIcon(new Color(0x2E, 0x8B, 0x57)));
            }
            return label;
        }
    }
}
