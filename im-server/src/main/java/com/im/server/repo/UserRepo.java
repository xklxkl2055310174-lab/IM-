package com.im.server.repo;

import com.im.server.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * 用户仓储（JDBC + PreparedStatement）。
 */
public final class UserRepo {

    /** 用户记录。 */
    public record User(long id, String username, String salt, String hash) {
    }

    private final Database db;

    public UserRepo(Database db) {
        this.db = db;
    }

    public Optional<User> findByUsername(String username) {
        return query("SELECT id, username, salt, password_hash FROM im_user WHERE username = ?", username);
    }

    public Optional<User> findById(long id) {
        return query("SELECT id, username, salt, password_hash FROM im_user WHERE id = ?", id);
    }

    private Optional<User> query(String sql, Object param) {
        try (Connection conn = db.connection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new User(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询用户失败", e);
        }
    }

    /** 插入用户并返回自增 id。 */
    public long insert(String username, String salt, String hash, long createdAt) {
        String sql = "INSERT INTO im_user (username, salt, password_hash, created_at) VALUES (?, ?, ?, ?)";
        try (Connection conn = db.connection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, salt);
            ps.setString(3, hash);
            ps.setLong(4, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new IllegalStateException("未返回自增主键");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("插入用户失败", e);
        }
    }
}
