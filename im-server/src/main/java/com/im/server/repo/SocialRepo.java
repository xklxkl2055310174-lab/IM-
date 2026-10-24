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
 * 社交仓储：好友（双向）+ 群组（含成员）。
 *
 * <p>加好友与建群均为事务操作：任一步失败整体回滚，避免出现"单向好友"或"有群无主"。</p>
 */
public final class SocialRepo {

    /** 群条目。 */
    public record GroupRow(long id, String name, int memberCount) {
    }

    /** 群成员条目。 */
    public record MemberRow(long userId, String username) {
    }

    private final Database db;

    public SocialRepo(Database db) {
        this.db = db;
    }

    // ---------- 好友 ----------

    /** 事务内双向写入好友关系；重复添加视为成功。 */
    public void addFriendPair(long a, long b, long createdAt) {
        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);
            try {
                insertFriend(conn, a, b, createdAt);
                insertFriend(conn, b, a, createdAt);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("添加好友失败", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("添加好友失败", e);
        }
    }

    private void insertFriend(Connection conn, long userId, long friendId, long createdAt) throws SQLException {
        String sql = "INSERT INTO friend (user_id, friend_id, created_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, friendId);
            ps.setLong(3, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            // 主键冲突视为已存在
            if (!String.valueOf(e.getSQLState()).startsWith("23")) {
                throw e;
            }
        }
    }

    /** 好友 id 列表。 */
    public List<Long> friendIds(long userId) {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT friend_id FROM friend WHERE user_id = ? ORDER BY friend_id")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询好友失败", e);
        }
        return ids;
    }

    // ---------- 群组 ----------

    /** 事务内建群并把创建者加入成员表。 */
    public long createGroup(String name, long ownerId, long createdAt) {
        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);
            try {
                long groupId;
                String sql = "INSERT INTO chat_group (name, owner_id, created_at) VALUES (?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name);
                    ps.setLong(2, ownerId);
                    ps.setLong(3, createdAt);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("未返回群 id");
                        }
                        groupId = keys.getLong(1);
                    }
                }
                addMember(conn, groupId, ownerId, createdAt);
                conn.commit();
                return groupId;
            } catch (SQLException e) {
                conn.rollback();
                throw new IllegalStateException("建群失败", e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("建群失败", e);
        }
    }

    private void addMember(Connection conn, long groupId, long userId, long joinedAt) throws SQLException {
        String sql = "INSERT INTO group_member (group_id, user_id, joined_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            ps.setLong(3, joinedAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            if (!String.valueOf(e.getSQLState()).startsWith("23")) {
                throw e;
            }
        }
    }

    /** 加群；返回 true 表示新加入，false 表示已是成员。 */
    public boolean join(long groupId, long userId, long joinedAt) {
        if (isMember(groupId, userId)) {
            return false;
        }
        try (Connection conn = db.connection()) {
            addMember(conn, groupId, userId, joinedAt);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("加群失败", e);
        }
    }

    public boolean groupExists(long groupId) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM chat_group WHERE id = ?")) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询群失败", e);
        }
    }

    public String groupName(long groupId) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM chat_group WHERE id = ?")) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询群名失败", e);
        }
    }

    public boolean isMember(long groupId, long userId) {
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM group_member WHERE group_id = ? AND user_id = ?")) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询群成员失败", e);
        }
    }

    /** 我加入的群（带成员数）。 */
    public List<GroupRow> groupsOf(long userId) {
        String sql = "SELECT g.id, g.name, (SELECT COUNT(*) FROM group_member m2 WHERE m2.group_id = g.id) "
                + "FROM chat_group g JOIN group_member m ON m.group_id = g.id "
                + "WHERE m.user_id = ? ORDER BY g.id";
        List<GroupRow> rows = new ArrayList<>();
        try (Connection conn = db.connection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new GroupRow(rs.getLong(1), rs.getString(2), rs.getInt(3)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询群列表失败", e);
        }
        return rows;
    }

    /** 群成员（带用户名）。 */
    public List<MemberRow> membersOf(long groupId) {
        String sql = "SELECT u.id, u.username FROM group_member m JOIN im_user u ON u.id = m.user_id "
                + "WHERE m.group_id = ? ORDER BY u.id";
        List<MemberRow> rows = new ArrayList<>();
        try (Connection conn = db.connection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new MemberRow(rs.getLong(1), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询群成员失败", e);
        }
        return rows;
    }
}
