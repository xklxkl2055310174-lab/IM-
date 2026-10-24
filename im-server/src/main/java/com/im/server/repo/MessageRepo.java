package com.im.server.repo;

import com.im.server.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息仓储：message（消息本体）+ offline_message（收件箱）。
 *
 * <p>{@code msg_id} 唯一索引保证重发不重复入库；收件箱以 {@code (user_id, id)} 索引支持按序分批拉取。</p>
 */
public final class MessageRepo {

    /** 收件箱行。 */
    public record InboxRow(long id, long messageId, String msgId, long fromUser, String content, long createdAt) {
    }

    /** 历史消息行。 */
    public record HistoryRow(long id, String msgId, long fromUser, String content, long createdAt) {
    }

    private final Database db;

    public MessageRepo(Database db) {
        this.db = db;
    }

    /** msg_id 是否已存在（幂等判断）。 */
    public boolean existsMsgId(String msgId) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM message WHERE msg_id = ?")) {
            ps.setString(1, msgId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 msg_id 失败", e);
        }
    }

    /** 统计相同 msg_id 的行数（用于幂等测试）。 */
    public int countByMsgId(String msgId) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM message WHERE msg_id = ?")) {
            ps.setString(1, msgId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计 msg_id 失败", e);
        }
    }

    /** 插入消息本体，返回自增 id。 */
    public long insertMessage(String msgId, int kind, long fromUser, Long toUser, Long groupId,
                              String content, long createdAt) {
        String sql = "INSERT INTO message (msg_id, kind, from_user, to_user, group_id, content, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, msgId);
            ps.setInt(2, kind);
            ps.setLong(3, fromUser);
            if (toUser == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, toUser);
            }
            if (groupId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, groupId);
            }
            ps.setString(6, content);
            ps.setLong(7, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new IllegalStateException("未返回自增主键");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("插入消息失败", e);
        }
    }

    /** 写一条收件箱记录。 */
    public void insertInbox(long userId, long messageId, String msgId, long fromUser, String content, long createdAt) {
        String sql = "INSERT INTO offline_message (user_id, message_id, msg_id, from_user, content, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.connection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, messageId);
            ps.setString(3, msgId);
            ps.setLong(4, fromUser);
            ps.setString(5, content);
            ps.setLong(6, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写收件箱失败", e);
        }
    }

    /** 按 id 升序取一批收件箱记录。 */
    public List<InboxRow> listInbox(long userId, int limit) {
        String sql = "SELECT id, message_id, msg_id, from_user, content, created_at FROM offline_message "
                + "WHERE user_id = ? ORDER BY id ASC LIMIT ?";
        List<InboxRow> rows = new ArrayList<>();
        try (Connection conn = db.connection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new InboxRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                            rs.getLong(4), rs.getString(5), rs.getLong(6)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询收件箱失败", e);
        }
        return rows;
    }

    /** 删除一条收件箱记录（DELIVER_ACK 后调用）。 */
    public boolean deleteInbox(long userId, String msgId) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM offline_message WHERE user_id = ? AND msg_id = ?")) {
            ps.setLong(1, userId);
            ps.setString(2, msgId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("删除收件箱失败", e);
        }
    }

    /** 剩余收件箱条数。 */
    public int countInbox(long userId) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM offline_message WHERE user_id = ?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计收件箱失败", e);
        }
    }

    /** 私聊历史：只返回双方之间的消息，按 id 倒序（最新在前），游标 beforeId（<=0 表示不限）。 */
    public List<HistoryRow> historyPrivate(long userId, long peerId, long beforeId, int limit) {
        String sql = "SELECT id, msg_id, from_user, content, created_at FROM message "
                + "WHERE kind = 1 AND (? <= 0 OR id < ?) "
                + "AND ((from_user = ? AND to_user = ?) OR (from_user = ? AND to_user = ?)) "
                + "ORDER BY id DESC LIMIT ?";
        return history(sql, ps -> {
            ps.setLong(1, beforeId);
            ps.setLong(2, beforeId);
            ps.setLong(3, userId);
            ps.setLong(4, peerId);
            ps.setLong(5, peerId);
            ps.setLong(6, userId);
            ps.setInt(7, limit);
        });
    }

    /** 群聊历史：调用方必须先校验群成员身份；游标 beforeId（<=0 表示不限）。 */
    public List<HistoryRow> historyGroup(long groupId, long beforeId, int limit) {
        String sql = "SELECT id, msg_id, from_user, content, created_at FROM message "
                + "WHERE kind = 2 AND group_id = ? AND (? <= 0 OR id < ?) ORDER BY id DESC LIMIT ?";
        return history(sql, ps -> {
            ps.setLong(1, groupId);
            ps.setLong(2, beforeId);
            ps.setLong(3, beforeId);
            ps.setInt(4, limit);
        });
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<HistoryRow> history(String sql, Binder binder) {
        List<HistoryRow> rows = new ArrayList<>();
        try (Connection conn = db.connection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HistoryRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
                            rs.getString(4), rs.getLong(5)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询历史消息失败", e);
        }
        return rows;
    }
}
