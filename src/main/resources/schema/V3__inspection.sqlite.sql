-- =====================================================================
-- V3 定时巡检 —— SQLite 方言
-- 由 SchemaInitializer 在 schema_version < 3 时按当前方言执行一次。
--
-- 口径（与 V1 完全一致，勿漂移）：
--   * 布尔列一律 INTEGER 0/1；
--   * 时间列一律 TEXT 存 ISO-8601（字典序 = 时间序，ticker 的 next_fire_at <= ?
--     直接做字符串比较，不需要任何日期函数）；
--   * value / threshold 一律 TEXT（数值与枚举统一串存，避免方言数值类型漂移）；
--   * 不声明外键（与 V1 一致，关联由代码保证）。
--
-- 索引策略：SQLite 的 CREATE TABLE 不支持内联 INDEX 子句，故索引独立成句并带
-- IF NOT EXISTS（SQLite 支持该语法），重复执行为 no-op。MySQL 方言无
-- CREATE INDEX IF NOT EXISTS，索引改为内联进 CREATE TABLE（见对应脚本）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS inspection_rule (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  cluster_id    INTEGER      NOT NULL,
  name          TEXT         NOT NULL,
  enabled       INTEGER      NOT NULL DEFAULT 1,
  severity      TEXT         NOT NULL,
  metric_scope  TEXT         NOT NULL,
  scope_filter  TEXT         NULL,
  condition_json TEXT        NOT NULL,
  cron_expr     TEXT         NOT NULL,
  next_fire_at  TEXT         NULL,                             -- null = 待对账
  description   TEXT         NULL,
  created_at    TEXT         NOT NULL,
  updated_at    TEXT         NOT NULL,
  CONSTRAINT uk_inspection_rule_cluster_name UNIQUE (cluster_id, name)
);

CREATE INDEX IF NOT EXISTS idx_inspection_rule_due
  ON inspection_rule (enabled, next_fire_at);

CREATE TABLE IF NOT EXISTS rule_condition (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  rule_id      INTEGER NOT NULL,
  seq          INTEGER NOT NULL,
  metric_key   TEXT    NOT NULL,
  operator     TEXT    NOT NULL,
  threshold    TEXT    NOT NULL,
  window_count INTEGER NULL,                                   -- null = 瞬时
  window_match TEXT    NULL,                                   -- ALL / ANY
  combinator   TEXT    NOT NULL                                -- AND / OR / END
);

CREATE INDEX IF NOT EXISTS idx_rule_condition_rule ON rule_condition (rule_id);

CREATE TABLE IF NOT EXISTS metric_sample (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  rule_id    INTEGER NOT NULL,
  metric_key TEXT    NOT NULL,
  scope_id   TEXT    NOT NULL,
  value      TEXT    NOT NULL,
  sampled_at TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_metric_sample_window
  ON metric_sample (rule_id, scope_id, sampled_at);

CREATE TABLE IF NOT EXISTS rule_state (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  rule_id           INTEGER NOT NULL,
  scope_id          TEXT    NOT NULL,
  current_state     TEXT    NOT NULL,                          -- OK / FIRING
  last_evaluated_at TEXT    NOT NULL,
  last_fired_at     TEXT    NULL,
  last_recovered_at TEXT    NULL,
  CONSTRAINT uk_rule_state_rule_scope UNIQUE (rule_id, scope_id)
);

CREATE TABLE IF NOT EXISTS alert_record (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  rule_id         INTEGER NOT NULL,
  cluster_id      INTEGER NOT NULL,                            -- 集群快照，供全局告警中心过滤
  scope_id        TEXT    NOT NULL,
  severity        TEXT    NOT NULL,
  state           TEXT    NOT NULL,                            -- FIRING / RECOVERED
  title           TEXT    NOT NULL,
  message         TEXT    NULL,
  fired_at        TEXT    NOT NULL,
  recovered_at    TEXT    NULL,
  acknowledged_at TEXT    NULL,
  acknowledged_by TEXT    NULL
);

CREATE INDEX IF NOT EXISTS idx_alert_record_state_fired ON alert_record (state, fired_at);
CREATE INDEX IF NOT EXISTS idx_alert_record_cluster ON alert_record (cluster_id);

CREATE TABLE IF NOT EXISTS alert_channel (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL,
  type        TEXT    NOT NULL,                                -- INSITE / EMAIL / WEBHOOK / IM_BOT
  config_json TEXT    NULL,
  enabled     INTEGER NOT NULL DEFAULT 1,
  CONSTRAINT uk_alert_channel_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS rule_channel (
  rule_id    INTEGER NOT NULL,
  channel_id INTEGER NOT NULL,
  PRIMARY KEY (rule_id, channel_id)
);

CREATE TABLE IF NOT EXISTS alert_dispatch_log (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  alert_id      INTEGER NOT NULL,
  channel_id    INTEGER NOT NULL,
  status        TEXT    NOT NULL,                              -- SUCCESS / FAILED
  error_message TEXT    NULL,
  created_at    TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_alert_dispatch_log_alert ON alert_dispatch_log (alert_id);
