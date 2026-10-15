-- =====================================================================
-- V3 定时巡检 —— MySQL 方言
-- 与 SQLite/PG 版的差异：
--   * 自增主键 BIGINT AUTO_INCREMENT；
--   * 时间列 VARCHAR(32)（MySQL 的 TEXT 不能进索引键、不能有默认值）；
--   * 索引全部内联进 CREATE TABLE —— MySQL 的独立索引语句不支持 IF NOT EXISTS,
--     独立成句时重复执行必然报 "Duplicate key name"。内联后由
--     CREATE TABLE IF NOT EXISTS 保证整句在表已存在时是 no-op。
-- 口径（布尔 INT 0/1、时间 TEXT ISO-8601 字符串、threshold/value 串存）三方言统一。
-- =====================================================================

CREATE TABLE IF NOT EXISTS inspection_rule (
  id             BIGINT NOT NULL AUTO_INCREMENT,
  cluster_id     BIGINT NOT NULL,
  name           VARCHAR(128) NOT NULL,
  enabled        INT          NOT NULL DEFAULT 1,
  severity       VARCHAR(16)  NOT NULL,
  metric_scope   VARCHAR(32)  NOT NULL,
  scope_filter   VARCHAR(256) NULL,
  condition_json TEXT         NOT NULL,
  cron_expr      VARCHAR(64)  NOT NULL,
  next_fire_at   VARCHAR(32)  NULL,
  description    VARCHAR(512) NULL,
  created_at     VARCHAR(32)  NOT NULL,
  updated_at     VARCHAR(32)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inspection_rule_cluster_name (cluster_id, name),
  INDEX idx_inspection_rule_due (enabled, next_fire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rule_condition (
  id           BIGINT NOT NULL AUTO_INCREMENT,
  rule_id      BIGINT  NOT NULL,
  seq          INT     NOT NULL,
  metric_key   VARCHAR(64) NOT NULL,
  operator     VARCHAR(4)  NOT NULL,
  threshold    VARCHAR(64) NOT NULL,
  window_count INT     NULL,
  window_match VARCHAR(4)  NULL,
  combinator   VARCHAR(4)  NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_rule_condition_rule (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS metric_sample (
  id         BIGINT NOT NULL AUTO_INCREMENT,
  rule_id    BIGINT NOT NULL,
  metric_key VARCHAR(64)  NOT NULL,
  scope_id   VARCHAR(128) NOT NULL,
  value      VARCHAR(64)  NOT NULL,
  sampled_at VARCHAR(32)  NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_metric_sample_window (rule_id, scope_id, sampled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rule_state (
  id                BIGINT NOT NULL AUTO_INCREMENT,
  rule_id           BIGINT NOT NULL,
  scope_id          VARCHAR(128) NOT NULL,
  current_state     VARCHAR(8)   NOT NULL,
  last_evaluated_at VARCHAR(32)  NOT NULL,
  last_fired_at     VARCHAR(32)  NULL,
  last_recovered_at VARCHAR(32)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rule_state_rule_scope (rule_id, scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS alert_record (
  id              BIGINT NOT NULL AUTO_INCREMENT,
  rule_id         BIGINT NOT NULL,
  cluster_id      BIGINT NOT NULL,
  scope_id        VARCHAR(128) NOT NULL,
  severity        VARCHAR(16)  NOT NULL,
  state           VARCHAR(16)  NOT NULL,
  title           VARCHAR(256) NOT NULL,
  message         TEXT         NULL,
  fired_at        VARCHAR(32)  NOT NULL,
  recovered_at    VARCHAR(32)  NULL,
  acknowledged_at VARCHAR(32)  NULL,
  acknowledged_by VARCHAR(64)  NULL,
  PRIMARY KEY (id),
  INDEX idx_alert_record_state_fired (state, fired_at),
  INDEX idx_alert_record_cluster (cluster_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS alert_channel (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  name        VARCHAR(64) NOT NULL,
  type        VARCHAR(16) NOT NULL,
  config_json TEXT        NULL,
  enabled     INT         NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_alert_channel_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rule_channel (
  rule_id    BIGINT NOT NULL,
  channel_id BIGINT NOT NULL,
  PRIMARY KEY (rule_id, channel_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS alert_dispatch_log (
  id            BIGINT NOT NULL AUTO_INCREMENT,
  alert_id      BIGINT NOT NULL,
  channel_id    BIGINT NOT NULL,
  status        VARCHAR(16) NOT NULL,
  error_message TEXT        NULL,
  created_at    VARCHAR(32) NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_alert_dispatch_log_alert (alert_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
