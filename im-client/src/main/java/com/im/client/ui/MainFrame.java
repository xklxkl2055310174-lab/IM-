package com.im.client.ui;

import com.im.client.ClientConfig;
import com.im.client.net.ImConnection;
import com.im.client.service.AuthService;
import com.im.client.service.AuthService.Session;
import com.im.client.service.ChatService;
import com.im.client.service.ConnectionManager;
import com.im.client.service.ContactService;
import com.im.client.service.FileService;
import com.im.client.service.FriendService;
import com.im.client.service.GroupService;
import com.im.client.service.HistoryService;
import com.im.client.service.ReceiptService;
import com.im.common.MessageType;
import com.im.common.Packet;
import com.im.common.json.JsonUtil;
import com.im.common.message.ChatMessages.GroupMsg;
import com.im.common.message.ChatMessages.OfflineMsgItem;
import com.im.common.message.ChatMessages.OfflineMsgPush;
import com.im.common.message.ChatMessages.PrivateMsg;
import com.im.common.message.ContactMessages.FriendItem;
import com.im.common.message.ContactMessages.OnlineUser;
import com.im.common.message.GroupMessages.GroupItem;
import com.im.common.message.GroupMessages.GroupMember;
import com.im.common.message.SystemMessages.KickNotify;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主窗口：会话 / 好友 / 群聊 / 在线用户 四个标签页，右侧聊天面板，底部连接状态。
 */
public final class MainFrame extends JFrame {

    private static final String CARD_EMPTY = "__empty__";

    private final ClientConfig config;
    private final AuthService auth;
    private final Session session;
    private final ImConnection connection;
    private final ConnectionManager connectionManager;

    private final ChatService chat;
    private final ContactService contacts;
    private final FriendService friendService;
    private final GroupService groupService;
    private final HistoryService historyService;
    private final FileService fileService;
    private final ReceiptService receipts;

    private final Map<String, Conversation> conversationByKey = new HashMap<>();
    private final Map<String, ChatPanel> panelByKey = new HashMap<>();
    private final Map<Long, String> friendNames = new HashMap<>();
    private final Map<Long, String> groupNames = new HashMap<>();

    private final DefaultListModel<Conversation> conversationModel = new DefaultListModel<>();
    private final DefaultListModel<FriendItem> friendModel = new DefaultListModel<>();
    private final DefaultListModel<GroupItem> groupModel = new DefaultListModel<>();
    private final DefaultListModel<OnlineUser> onlineModel = new DefaultListModel<>();
    private final JList<Conversation> conversationList = new JList<>(conversationModel);
    private final JList<FriendItem> friendList = new JList<>(friendModel);
    private final JList<GroupItem> groupList = new JList<>(groupModel);
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
        this.connectionManager = new ConnectionManager(connection, auth, config.host(), config.port());
        this.chat = new ChatService(connection, session.userId());
        this.contacts = new ContactService(connection);
        this.friendService = new FriendService(connection);
        this.groupService = new GroupService(connection);
        this.historyService = new HistoryService(connection);
        this.fileService = new FileService(config.host(), config.filePort(), session.token());
        this.receipts = new ReceiptService(connection::send);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 620));
        setContentPane(buildContent());
        setLocationRelativeTo(null);

        wireEvents();
        connectionManager.enableAutoReconnect(session.token());
        TrayNotifier.install();
        refreshFriends();
        refreshGroups();
        refreshOnlineUsers();
    }

    // ---------- 布局 ----------

    private JPanel buildContent() {
        conversationList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        onlineList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        conversationList.setCellRenderer(new ConversationRenderer());
        friendList.setCellRenderer(new FriendRenderer());
        groupList.setCellRenderer(new GroupRenderer());
        onlineList.setCellRenderer(new OnlineRenderer());

        tabs.addTab("会话", new JScrollPane(conversationList));
        tabs.addTab("好友", wrapWithButtons(friendList,
                new JButton[]{new JButton("添加好友")}, new Runnable[]{this::addFriendDialog}));
        tabs.addTab("群聊", wrapWithButtons(groupList,
                new JButton[]{new JButton("建群"), new JButton("加群")},
                new Runnable[]{this::createGroupDialog, this::joinGroupDialog}));
        tabs.addTab("在线用户", new JScrollPane(onlineList));
        tabs.addChangeListener(e -> {
            int index = tabs.getSelectedIndex();
            if (index == 1) {
                refreshFriends();
            } else if (index == 2) {
                refreshGroups();
            } else if (index == 3) {
                refreshOnlineUsers();
            }
        });

        JPanel left = new JPanel(new BorderLayout());
        left.setPreferredSize(new Dimension(240, 0));
        left.add(tabs, BorderLayout.CENTER);

        JPanel empty = new JPanel(new BorderLayout());
        JLabel hint = new JLabel("从左侧选择会话，或双击好友/群聊开始聊天", JLabel.CENTER);
        hint.setForeground(new Color(0x99, 0x99, 0x99));
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 15f));
        empty.add(hint, BorderLayout.CENTER);
        cards.add(empty, CARD_EMPTY);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        statusLabel.setFont(statusLabel.getFont().deriveFont(12f));
        statusLabel.setForeground(new Color(0x55, 0x55, 0x55));

        JPanel root = new JPanel(new BorderLayout());
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, cards);
        split.setDividerLocation(240);
        split.setResizeWeight(0);
        root.add(split, BorderLayout.CENTER);
        root.add(statusLabel, BorderLayout.SOUTH);
        return root;
    }

    private JPanel wrapWithButtons(JList<?> list, JButton[] buttons, Runnable[] actions) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        for (int i = 0; i < buttons.length; i++) {
            JButton button = buttons[i];
            final int index = i;
            if (index < actions.length) {
                button.addActionListener(e -> actions[index].run());
            }
            bar.add(button);
        }
        panel.add(bar, BorderLayout.SOUTH);
        return panel;
    }

    // ---------- 事件 ----------

    private void wireEvents() {
        conversationList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Conversation selected = conversationList.getSelectedValue();
                if (selected != null) {
                    openConversation(selected);
                }
            }
        });

        friendList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    FriendItem friend = friendList.getSelectedValue();
                    if (friend != null && friend.userId() > 0) {
                        openConversation(conversationFor(friend.userId(), friend.username(), false));
                    }
                }
            }
        });

        groupList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    GroupItem group = groupList.getSelectedValue();
                    if (group != null && group.groupId() > 0) {
                        openConversation(conversationFor(group.groupId(), group.name(), true));
                    }
                }
            }
        });

        onlineList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    OnlineUser user = onlineList.getSelectedValue();
                    if (user != null && user.userId() != session.userId()) {
                        openConversation(conversationFor(user.userId(), user.username(), false));
                    }
                }
            }
        });

        // 可靠接收：先注册确认器，再注册界面回调
        connection.on(MessageType.PRIVATE_MSG, receipts::accept);
        connection.on(MessageType.GROUP_MSG, receipts::accept);
        connection.on(MessageType.OFFLINE_MSG_PUSH, receipts::accept);

        chat.onIncoming(this::onPrivateMessage);
        chat.onIncomingGroup(this::onGroupMessage);
        connection.on(MessageType.OFFLINE_MSG_PUSH, this::onOfflinePush);
        connection.on(MessageType.KICK_NOTIFY, packet -> UiThread.run(() -> onKicked(packet)));

        connectionManager.addListener(new ConnectionManager.Listener() {
            @Override
            public void onStatus(String text) {
                UiThread.run(() -> statusLabel.setText("连接状态：" + text));
            }

            @Override
            public void onReconnected() {
                UiThread.run(() -> {
                    statusLabel.setText("连接状态：已恢复");
                    refreshFriends();
                    refreshGroups();
                    refreshOnlineUsers();
                });
            }

            @Override
            public void onLoginRequired(String reason) {
                UiThread.run(() -> backToLogin(reason));
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
    }

    // ---------- 会话 ----------

    private Conversation conversationFor(long peerId, String name, boolean group) {
        String key = (group ? "g" : "u") + peerId;
        return conversationByKey.computeIfAbsent(key, k -> {
            Conversation conversation = new Conversation(key, peerId, name, group);
            conversationModel.addElement(conversation);
            return conversation;
        });
    }

    private void openConversation(Conversation conversation) {
        ChatPanel panel = panelByKey.get(conversation.key());
        if (panel == null) {
            panel = new ChatPanel(conversation.peerId(), conversation.name(), conversation.group(),
                    session.userId(), chat, historyService, fileService);
            panelByKey.put(conversation.key(), panel);
            cards.add(conversation.group() ? groupCard(panel, conversation) : panel, conversation.key());
            panel.loadInitialHistory();
        }
        ((CardLayout) cards.getLayout()).show(cards, conversation.key());
        conversation.setUnread(0);
        conversationList.repaint();
        updateTitleUnread();
    }

    private JPanel groupCard(ChatPanel panel, Conversation conversation) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.CENTER);
        DefaultListModel<GroupMember> model = new DefaultListModel<>();
        JList<GroupMember> members = new JList<>(model);
        members.setCellRenderer(new MemberRenderer());
        members.setFont(Theme.uiFont());
        JPanel side = new JPanel(new BorderLayout());
        JLabel title = new JLabel("群成员");
        title.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        side.add(title, BorderLayout.NORTH);
        side.add(new JScrollPane(members), BorderLayout.CENTER);
        side.setPreferredSize(new Dimension(160, 0));
        wrapper.add(side, BorderLayout.EAST);
        wrapper.putClientProperty("membersModel", model);
        wrapper.putClientProperty("panel", panel);
        loadGroupMembers(conversation.peerId(), wrapper);
        return wrapper;
    }

    private void onPrivateMessage(PrivateMsg msg) {
        UiThread.run(() -> {
            Conversation conversation = conversationFor(msg.fromUserId(),
                    friendNames.getOrDefault(msg.fromUserId(), "用户 " + msg.fromUserId()), false);
            ChatPanel panel = panelByKey.get(conversation.key());
            if (panel == null) {
                panel = new ChatPanel(conversation.peerId(), conversation.name(), false,
                        session.userId(), chat, historyService, fileService);
                panelByKey.put(conversation.key(), panel);
                cards.add(panel, conversation.key());
                panel.loadInitialHistory();
            }
            panel.appendIncoming(msg);
            bumpUnreadIfHidden(conversation, "新消息", msg.content());
        });
    }

    private void onGroupMessage(GroupMsg msg) {
        UiThread.run(() -> {
            Conversation conversation = conversationFor(msg.groupId(),
                    groupNames.getOrDefault(msg.groupId(), "群 " + msg.groupId()), true);
            ChatPanel panel = panelByKey.get(conversation.key());
            if (panel == null) {
                panel = new ChatPanel(conversation.peerId(), conversation.name(), true,
                        session.userId(), chat, historyService, fileService);
                panelByKey.put(conversation.key(), panel);
                cards.add(groupCard(panel, conversation), conversation.key());
                panel.loadInitialHistory();
            }
            panel.appendIncomingGroup(msg);
            bumpUnreadIfHidden(conversation, "群消息", msg.content());
        });
    }

    private void onOfflinePush(Packet packet) {
        UiThread.run(() -> {
            try {
                OfflineMsgPush push = JsonUtil.fromJson(packet.payload(), OfflineMsgPush.class);
                for (OfflineMsgItem item : push.messages()) {
                    Conversation conversation = conversationFor(item.fromUserId(),
                            friendNames.getOrDefault(item.fromUserId(), "用户 " + item.fromUserId()), false);
                    ChatPanel panel = panelByKey.get(conversation.key());
                    if (panel == null) {
                        panel = new ChatPanel(conversation.peerId(), conversation.name(), false,
                                session.userId(), chat, historyService, fileService);
                        panelByKey.put(conversation.key(), panel);
                        cards.add(panel, conversation.key());
                        panel.loadInitialHistory();
                    }
                    panel.appendHistory(item.id(), item.fromUserId(), item.content(), item.timestamp());
                    conversation.incrementUnread();
                }
                conversationList.repaint();
                updateTitleUnread();
                if (push.total() > 0) {
                    TrayNotifier.notify("离线消息", "收到 " + push.total() + " 条离线消息");
                }
            } catch (Exception ignored) {
                // 忽略解析失败
            }
        });
    }

    private void bumpUnreadIfHidden(Conversation conversation, String title, String content) {
        boolean visible = conversationList.getSelectedValue() == conversation
                && cards.isVisible();
        if (!visible) {
            conversation.incrementUnread();
            conversationList.repaint();
            updateTitleUnread();
            TrayNotifier.notify(title, content);
        }
    }

    private void updateTitleUnread() {
        int unread = conversationByKey.values().stream().mapToInt(Conversation::unread).sum();
        setTitle("IM - " + session.username() + " (" + session.userId() + ")"
                + (unread > 0 ? "  ● " + unread + " 条未读" : ""));
    }

    // ---------- 好友 / 群组 ----------

    private void addFriendDialog() {
        String name = JOptionPane.showInputDialog(this, "输入对方用户名：", "添加好友",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        friendService.addFriend(name.trim()).whenComplete((response, error) -> UiThread.run(() -> {
            if (error != null) {
                showError("添加失败: " + rootMessage(error));
            } else if (response.code() != 0) {
                showError(response.message());
            } else {
                JOptionPane.showMessageDialog(this, "已添加好友：" + response.friendUsername());
                refreshFriends();
            }
        }));
    }

    private void createGroupDialog() {
        String name = JOptionPane.showInputDialog(this, "群名称：", "创建群聊", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        groupService.create(name.trim()).whenComplete((response, error) -> UiThread.run(() -> {
            if (error != null) {
                showError("建群失败: " + rootMessage(error));
            } else if (response.code() != 0) {
                showError(response.message());
            } else {
                JOptionPane.showMessageDialog(this, "建群成功，群号：" + response.groupId());
                refreshGroups();
            }
        }));
    }

    private void joinGroupDialog() {
        String input = JOptionPane.showInputDialog(this, "输入群号：", "加入群聊", JOptionPane.PLAIN_MESSAGE);
        if (input == null || input.isBlank()) {
            return;
        }
        long groupId;
        try {
            groupId = Long.parseLong(input.trim());
        } catch (NumberFormatException e) {
            showError("群号必须是数字");
            return;
        }
        groupService.join(groupId).whenComplete((response, error) -> UiThread.run(() -> {
            if (error != null) {
                showError("加群失败: " + rootMessage(error));
            } else if (response.code() != 0) {
                showError(response.message());
            } else {
                JOptionPane.showMessageDialog(this, "已加入群 " + groupId);
                refreshGroups();
            }
        }));
    }

    /** 好友列表：在线的排前面，离线的置灰。 */
    private void refreshFriends() {
        friendService.friends().whenComplete((response, error) -> UiThread.run(() -> {
            if (error != null || response == null || response.code() != 0) {
                return;
            }
            friendModel.clear();
            friendNames.clear();
            List<FriendItem> items = new java.util.ArrayList<>(response.friends());
            items.sort((a, b) -> Boolean.compare(b.online(), a.online()));
            for (FriendItem item : items) {
                friendModel.addElement(item);
                friendNames.put(item.userId(), item.username());
                Conversation conversation = conversationByKey.get("u" + item.userId());
                if (conversation != null) {
                    conversation.setOnline(item.online());
                    conversation.setName(item.username());
                }
            }
            if (items.isEmpty()) {
                friendModel.addElement(new FriendItem(0, "（暂无好友，点击下方添加）", false));
            }
            conversationList.repaint();
        }));
    }

    private void refreshGroups() {
        groupService.list().whenComplete((response, error) -> UiThread.run(() -> {
            if (error != null || response == null || response.code() != 0) {
                return;
            }
            groupModel.clear();
            groupNames.clear();
            for (GroupItem item : response.groups()) {
                groupModel.addElement(item);
                groupNames.put(item.groupId(), item.name());
            }
            if (response.groups().isEmpty()) {
                groupModel.addElement(new GroupItem(0, "（暂无群聊）", 0));
            }
        }));
    }

    private void loadGroupMembers(long groupId, JPanel groupCard) {
        @SuppressWarnings("unchecked")
        DefaultListModel<GroupMember> model =
                (DefaultListModel<GroupMember>) groupCard.getClientProperty("membersModel");
        ChatPanel panel = (ChatPanel) groupCard.getClientProperty("panel");
        groupService.members(groupId).whenComplete((response, error) -> UiThread.run(() -> {
            if (error != null || response == null || response.code() != 0) {
                return;
            }
            model.clear();
            for (GroupMember member : response.members()) {
                model.addElement(member);
            }
            panel.updateMembers(response.members());
        }));
    }

    private void refreshOnlineUsers() {
        contacts.onlineUsers().whenComplete((response, error) -> UiThread.run(() -> {
            onlineModel.clear();
            if (error != null || response == null || response.code() != 0) {
                return;
            }
            for (OnlineUser user : response.users()) {
                onlineModel.addElement(user);
                Conversation conversation = conversationByKey.get("u" + user.userId());
                if (conversation != null) {
                    conversation.setName(user.username());
                    conversation.setOnline(true);
                }
            }
            conversationList.repaint();
        }));
    }

    // ---------- 流程 ----------

    private void onKicked(Packet packet) {
        String message = "账号已在别处登录";
        try {
            message = JsonUtil.fromJson(packet.payload(), KickNotify.class).message();
        } catch (Exception ignored) {
            // 使用默认文案
        }
        JOptionPane.showMessageDialog(this, message, "已下线", JOptionPane.WARNING_MESSAGE);
        backToLogin("账号已在别处登录");
    }

    private void backToLogin(String reason) {
        connectionManager.disableAutoReconnect();
        chat.close();
        receipts.close();
        connection.close();
        dispose();
        LoginFrame login = new LoginFrame(config);
        login.setVisible(true);
        if (reason != null && !reason.isBlank()) {
            JOptionPane.showMessageDialog(login, reason, "提示", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void shutdown() {
        connectionManager.disableAutoReconnect();
        try {
            auth.logout();
        } catch (RuntimeException ignored) {
            // 忽略
        }
        chat.close();
        receipts.close();
        connectionManager.close();
        connection.close();
        dispose();
        new LoginFrame(config).setVisible(true);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "提示", JOptionPane.WARNING_MESSAGE);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    // ---------- 模型与渲染 ----------

    /** 会话条目。 */
    public static final class Conversation {
        private final String key;
        private final long peerId;
        private String name;
        private final boolean group;
        private int unread;
        private boolean online;

        Conversation(String key, long peerId, String name, boolean group) {
            this.key = key;
            this.peerId = peerId;
            this.name = name;
            this.group = group;
        }

        public String key() {
            return key;
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

        public boolean group() {
            return group;
        }

        public int unread() {
            return unread;
        }

        void incrementUnread() {
            unread++;
        }

        void setUnread(int unread) {
            this.unread = unread;
        }

        public boolean online() {
            return online;
        }

        void setOnline(boolean online) {
            this.online = online;
        }
    }

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

    private static final Color ONLINE = new Color(0x2E, 0x8B, 0x57);
    private static final Color OFFLINE = new Color(0xBB, 0xBB, 0xBB);

    private static final class ConversationRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focus) {
            javax.swing.JLabel label = (javax.swing.JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            if (value instanceof Conversation conversation) {
                label.setText((conversation.group() ? "[群] " : "") + conversation.name()
                        + (conversation.unread() > 0 ? "  [" + conversation.unread() + "]" : ""));
                label.setIcon(conversation.group() ? new DotIcon(OFFLINE)
                        : new DotIcon(conversation.online() ? ONLINE : OFFLINE));
            }
            return label;
        }
    }

    private static final class FriendRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focus) {
            javax.swing.JLabel label = (javax.swing.JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            if (value instanceof FriendItem friend) {
                label.setText(friend.username());
                label.setIcon(new DotIcon(friend.online() ? ONLINE : OFFLINE));
                if (!selected) {
                    label.setForeground(friend.online() ? Color.BLACK : new Color(0x99, 0x99, 0x99));
                }
            }
            return label;
        }
    }

    private static final class GroupRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focus) {
            javax.swing.JLabel label = (javax.swing.JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            if (value instanceof GroupItem group) {
                label.setText(group.name() + (group.memberCount() > 0
                        ? "（" + group.memberCount() + " 人）" : ""));
            }
            return label;
        }
    }

    private static final class OnlineRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focus) {
            javax.swing.JLabel label = (javax.swing.JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            if (value instanceof OnlineUser user) {
                label.setText(user.username());
                label.setIcon(new DotIcon(ONLINE));
            }
            return label;
        }
    }

    private static final class MemberRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focus) {
            javax.swing.JLabel label = (javax.swing.JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            if (value instanceof GroupMember member) {
                label.setText(member.username());
                label.setIcon(new DotIcon(member.online() ? ONLINE : OFFLINE));
            }
            return label;
        }
    }
}
