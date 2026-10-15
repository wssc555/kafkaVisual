-- =====================================================================
-- V3 定时巡检 —— PostgreSQL 方言
-- 与 SQLite 版的差异：自增主键 BIGSERIAL；唯一约束 / 索引写法与 PG 一致。
-- 口径（布尔 INTEGER 0/1、时间 TEXT ISO-8601、threshold/value 串存）三方言统一。
--
-- 索引策略：PG 的 CREATE TABLE 同样不支持内联 INDEX 子句，故索引独立成句并带
-- IF NOT EXISTS（PG 支持该语法），重复执行为 no-op。仅 MySQL 方言因缺少
-- CREATE INDEX IF NOT EXISTS 而把索引内联进 CREATE TABLE。
-- =====================================================================

CREATE TABLE IF NOT EXISTS inspection_rule (
  id             BIGSERIAL PRIMARY KEY,
  cluster_id     BIGINT  NOT NULL,
  name           TEXT    NOT NULL,
  enabled        INTEGER NOT NULL DEFAULT 1,
  severity       TEXT    NOT NULL,
  metric_scope   TEXT    NOT NULL,
  scope_filter   TEXT    NULL,
  condition_json TEXT    NOT NULL,
  cron_expr      TEXT    NOT NULL,
  next_fire_at   TEXT    NULL,
  description    TEXT    NULL,
  created_at     TEXT    NOT NULL,
  updated_at     TEXT    NOT NULL,
  CONSTRAINT uk_inspection_rule_cluster_name UNIQUE (cluster_id, name)
);

CREATE INDEX IF NOT EXISTS idx_inspection_rule_due
  ON inspection_rule (enabled, next_fire_at);

CREATE TABLE IF NOT EXISTS rule_condition (
  id           BIGSERIAL PRIMARY KEY,
  rule_id      BIGINT  NOT NULL,
  seq          INTEGER NOT NULL,
  metric_key   TEXT    NOT NULL,
  operator     TEXT    NOT NULL,
  threshold    TEXT    NOT NULL,
  window_count INTEGER NULL,
  window_match TEXT    NULL,
  combinator   TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rule_condition_rule ON rule_condition (rule_id);

CREATE TABLE IF NOT EXISTS metric_sample (
  id         BIGSERIAL PRIMARY KEY,
  rule_id    BIGINT  NOT NULL,
  metric_key TEXT    NOT NULL,
  scope_id   TEXT    NOT NULL,
  value      TEXT    NOT NULL,
  sampled_at TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_metric_sample_window
  ON metric_sample (rule_id, scope_id, sampled_at);

CREATE TABLE IF NOT EXISTS rule_state (
  id                BIGSERIAL PRIMARY KEY,
  rule_id           BIGINT  NOT NULL,
  scope_id          TEXT    NOT NULL,
  current_state     TEXT    NOT NULL,
  last_evaluated_at TEXT    NOT NULL,
  last_fired_at     TEXT    NULL,
  last_recovered_at TEXT    NULL,
  CONSTRAINT uk_rule_state_rule_scope UNIQUE (rule_id, scope_id)
);

CREATE TABLE IF NOT EXISTS alert_record (
  id              BIGSERIAL PRIMARY KEY,
  rule_id         BIGINT NOT NULL,
  cluster_id      BIGINT NOT NULL,
  scope_id        TEXT   NOT NULL,
  severity        TEXT   NOT NULL,
  state           TEXT   NOT NULL,
  title           TEXT   NOT NULL,
  message         TEXT   NULL,
  fired_at        TEXT   NOT NULL,
  recovered_at    TEXT   NULL,
  acknowledged_at TEXT   NULL,
  acknowledged_by TEXT   NULL
);

CREATE INDEX IF NOT EXISTS idx_alert_record_state_fired ON alert_record (state, fired_at);
CREATE INDEX IF NOT EXISTS idx_alert_record_cluster ON alert_record (cluster_id);

CREATE TABLE IF NOT EXISTS alert_channel (
  id          BIGSERIAL PRIMARY KEY,
  name        TEXT    NOT NULL,
  type        TEXT    NOT NULL,
  config_json TEXT    NULL,
  enabled     INTEGER NOT NULL DEFAULT 1,
  CONSTRAINT uk_alert_channel_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS rule_channel (
  rule_id    BIGINT NOT NULL,
  channel_id BIGINT NOT NULL,
  PRIMARY KEY (rule_id, channel_id)
);

CREATE TABLE IF NOT EXISTS alert_dispatch_log (
  id            BIGSERIAL PRIMARY KEY,
  alert_id      BIGINT NOT NULL,
  channel_id    BIGINT NOT NULL,
  status        TEXT   NOT NULL,
  error_message TEXT   NULL,
  created_at    TEXT   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_alert_dispatch_log_alert ON alert_dispatch_log (alert_id);
