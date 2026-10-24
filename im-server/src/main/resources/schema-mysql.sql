-- IM 建表脚本（MySQL 8）：索引单独创建，避免依赖 IF NOT EXISTS 的索引语法差异
CREATE TABLE IF NOT EXISTS im_user (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    salt          VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    created_at    BIGINT       NOT NULL,
    CONSTRAINT uk_user_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS friend (
    user_id    BIGINT NOT NULL,
    friend_id  BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_group (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    name       VARCHAR(128) NOT NULL,
    owner_id   BIGINT       NOT NULL,
    created_at BIGINT       NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS group_member (
    group_id  BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    joined_at BIGINT NOT NULL,
    PRIMARY KEY (group_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS message (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    msg_id     VARCHAR(64)   NOT NULL,
    kind       INT           NOT NULL,
    from_user  BIGINT        NOT NULL,
    to_user    BIGINT        NULL,
    group_id   BIGINT        NULL,
    content    VARCHAR(4000) NOT NULL,
    created_at BIGINT        NOT NULL,
    CONSTRAINT uk_message_msg_id UNIQUE (msg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_message_private ON message (from_user, to_user);
CREATE INDEX idx_message_group ON message (group_id);

CREATE TABLE IF NOT EXISTS offline_message (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id    BIGINT        NOT NULL,
    message_id BIGINT        NOT NULL,
    msg_id     VARCHAR(64)   NOT NULL,
    from_user  BIGINT        NOT NULL,
    content    VARCHAR(4000) NOT NULL,
    created_at BIGINT        NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_offline_user ON offline_message (user_id, id);

CREATE TABLE IF NOT EXISTS file_meta (
    file_id     VARCHAR(64)  PRIMARY KEY,
    file_name   VARCHAR(255) NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    md5         VARCHAR(32)  NOT NULL,
    uploader_id BIGINT       NOT NULL,
    receiver_id BIGINT       NULL,
    created_at  BIGINT       NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
