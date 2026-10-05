-- =====================================================================
-- V1 基础表 —— SQLite 方言（默认数据源）
-- 由 SchemaInitializer 在启动时按当前方言执行一次；幂等（IF NOT EXISTS）。
--
-- 口径说明：
--   * 布尔列一律 INTEGER 0/1（Java 侧按 int 读写，避免方言 BOOLEAN 映射差异）；
--   * 时间列一律 TEXT 存 ISO-8601（字典序 = 时间序，三方言通用）；
--   * 每集群归档表 msg_<clusterId> 不在这里，由 ClusterArchiveService 运行期动态建（见设计 §3.2）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS schema_version (
  version    INTEGER PRIMARY KEY,
  applied_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS cluster_config (
  id                     INTEGER PRIMARY KEY AUTOINCREMENT,
  name                   TEXT NOT NULL UNIQUE,                 -- 显示名，唯一
  bootstrap_servers      TEXT NOT NULL,
  security_protocol      TEXT NOT NULL DEFAULT '',             -- 空=无认证; SASL_PLAINTEXT / SASL_SSL
  sasl_mechanism         TEXT NOT NULL DEFAULT 'PLAIN',
  username               TEXT NOT NULL DEFAULT '',
  password_cipher        BLOB,                                 -- AES-GCM 密文, NULL=无密码
  zk_connect_string      TEXT NOT NULL DEFAULT '',             -- 空=该集群 KRaft 模式
  enabled                INTEGER NOT NULL DEFAULT 1,
  archive_enabled        INTEGER NOT NULL DEFAULT 1,
  archive_retention_days INTEGER NOT NULL DEFAULT 30,
  sort_order             INTEGER NOT NULL DEFAULT 0,
  created_at             TEXT NOT NULL,
  updated_at             TEXT NOT NULL
);

-- 归档 topic 台账：记录「曾经存在过」的 topic（含已从 Kafka 删除的）
CREATE TABLE IF NOT EXISTS topic_registry (
  cluster_id           INTEGER NOT NULL,
  topic_name           TEXT NOT NULL,
  first_seen_at        TEXT NOT NULL,
  last_seen_at         TEXT NOT NULL,
  last_partition_count INTEGER,
  deleted              INTEGER NOT NULL DEFAULT 0,             -- 1 = 已从 Kafka 消失（仅归档可查）
  PRIMARY KEY (cluster_id, topic_name)
);

CREATE TABLE IF NOT EXISTS favorite (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  cluster_id INTEGER NOT NULL,
  item_type  TEXT NOT NULL,                                    -- topic / group
  item_name  TEXT NOT NULL,
  UNIQUE (cluster_id, item_type, item_name)
);

CREATE TABLE IF NOT EXISTS ui_preference (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
