package com.im.server.repo;

import com.im.server.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 文件元数据仓储。文件本体按 fileId 存磁盘，原文件名只存库（防路径穿越）。
 */
public final class FileRepo {

    /** 文件元数据。 */
    public record FileMeta(String fileId, String fileName, long size, String md5,
                           long uploaderId, Long receiverId, long createdAt) {
    }

    private final Database db;

    public FileRepo(Database db) {
        this.db = db;
    }

    public void insert(FileMeta meta) {
        String sql = "INSERT INTO file_meta (file_id, file_name, size_bytes, md5, uploader_id, receiver_id, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.connection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, meta.fileId());
            ps.setString(2, meta.fileName());
            ps.setLong(3, meta.size());
            ps.setString(4, meta.md5());
            ps.setLong(5, meta.uploaderId());
            if (meta.receiverId() == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, meta.receiverId());
            }
            ps.setLong(7, meta.createdAt());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("写入文件元数据失败", e);
        }
    }

    public Optional<FileMeta> find(String fileId) {
        String sql = "SELECT file_id, file_name, size_bytes, md5, uploader_id, receiver_id, created_at "
                + "FROM file_meta WHERE file_id = ?";
        try (Connection conn = db.connection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long receiver = rs.getLong(6);
                Long receiverId = rs.wasNull() ? null : receiver;
                return Optional.of(new FileMeta(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getLong(5), receiverId, rs.getLong(7)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询文件元数据失败", e);
        }
    }
}
