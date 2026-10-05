-- =====================================================================
-- V1 基础表 —— PostgreSQL 方言（外部库，用户手动配置连接）
-- 与 SQLite 版的差异：自增主键 BIGSERIAL、大对象 BYTEA、
-- 布尔列同样用 INTEGER 0/1（Java 侧统一按 int 读写）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS schema_version (
  version    INTEGER PRIMARY KEY,
  applied_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS cluster_config (
  id                     BIGSERIAL PRIMARY KEY,
  name                   TEXT NOT NULL,
  bootstrap_servers      TEXT NOT NULL,
  security_protocol      TEXT NOT NULL DEFAULT '',
  sasl_mechanism         TEXT NOT NULL DEFAULT 'PLAIN',
  username               TEXT NOT NULL DEFAULT '',
  password_cipher        BYTEA,
  zk_connect_string      TEXT NOT NULL DEFAULT '',
  enabled                INTEGER NOT NULL DEFAULT 1,
  archive_enabled        INTEGER NOT NULL DEFAULT 1,
  archive_retention_days INTEGER NOT NULL DEFAULT 30,
  sort_order             INTEGER NOT NULL DEFAULT 0,
  created_at             TEXT NOT NULL,
  updated_at             TEXT NOT NULL,
  CONSTRAINT uk_cluster_config_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS topic_registry (
  cluster_id           BIGINT NOT NULL,
  topic_name           TEXT NOT NULL,
  first_seen_at        TEXT NOT NULL,
  last_seen_at         TEXT NOT NULL,
  last_partition_count INTEGER,
  deleted              INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (cluster_id, topic_name)
);

CREATE TABLE IF NOT EXISTS favorite (
  id         BIGSERIAL PRIMARY KEY,
  cluster_id BIGINT NOT NULL,
  item_type  TEXT NOT NULL,
  item_name  TEXT NOT NULL,
  CONSTRAINT uk_favorite_item UNIQUE (cluster_id, item_type, item_name)
);

-- "key" 加引号：PG 中 KEY 是非保留关键字，加引号只为与三方言口径一致，避免有人改成保留字后失效
CREATE TABLE IF NOT EXISTS ui_preference (
  "key" TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
