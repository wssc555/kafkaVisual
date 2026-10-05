-- =====================================================================
-- V1 基础表 —— MySQL 方言（外部库，用户手动配置连接）
-- 与 SQLite 版的差异：自增主键 BIGINT AUTO_INCREMENT、大对象 LONGBLOB/LONGTEXT、
-- 时间列 VARCHAR(32)（MySQL 的 TEXT 不能作主键、不能有默认值）。
-- `key` 是 MySQL 保留字，必须反引号。
-- =====================================================================

CREATE TABLE IF NOT EXISTS schema_version (
  version    INT PRIMARY KEY,
  applied_at VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS cluster_config (
  id                     BIGINT NOT NULL AUTO_INCREMENT,
  name                   VARCHAR(255) NOT NULL,
  bootstrap_servers      VARCHAR(1024) NOT NULL,
  security_protocol      VARCHAR(64)  NOT NULL DEFAULT '',
  sasl_mechanism         VARCHAR(64)  NOT NULL DEFAULT 'PLAIN',
  username               VARCHAR(255) NOT NULL DEFAULT '',
  password_cipher        LONGBLOB     NULL,
  zk_connect_string      VARCHAR(1024) NOT NULL DEFAULT '',
  enabled                INT          NOT NULL DEFAULT 1,
  archive_enabled        INT          NOT NULL DEFAULT 1,
  archive_retention_days INT          NOT NULL DEFAULT 30,
  sort_order             INT          NOT NULL DEFAULT 0,
  created_at             VARCHAR(32)  NOT NULL,
  updated_at             VARCHAR(32)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cluster_config_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS topic_registry (
  cluster_id           BIGINT       NOT NULL,
  topic_name           VARCHAR(255) NOT NULL,
  first_seen_at        VARCHAR(32)  NOT NULL,
  last_seen_at         VARCHAR(32)  NOT NULL,
  last_partition_count INT          NULL,
  deleted              INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (cluster_id, topic_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS favorite (
  id         BIGINT NOT NULL AUTO_INCREMENT,
  cluster_id BIGINT       NOT NULL,
  item_type  VARCHAR(16)  NOT NULL,
  -- 512 而非 255:INSERT IGNORE 会把"超长截断"降级为 warning 静默丢数据,
  -- 列宽必须稳装所有合法值(topic 名上限 249,group.id 常见 <200)
  item_name  VARCHAR(512) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_favorite_item (cluster_id, item_type, item_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ui_preference (
  `key` VARCHAR(255) NOT NULL,
  `value` TEXT       NOT NULL,
  PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
