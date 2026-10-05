-- =====================================================================
-- V2 多认证方式扩展 —— PostgreSQL 方言（外部库，用户手动配置连接）
-- 与 SQLite 版的差异：大对象 BYTEA、文本 TEXT（一致）、布尔列同样 INTEGER 0/1。
-- 幂等性由 SchemaInitializer 的 schema_version 守卫保证（本脚本不自带 IF NOT EXISTS，
-- 因为 PG 虽支持 ADD COLUMN IF NOT EXISTS，但为了三方言"同一套执行语义"而统一不用）。
-- =====================================================================

ALTER TABLE cluster_config ADD COLUMN auth_type                TEXT    NOT NULL DEFAULT 'NONE';
ALTER TABLE cluster_config ADD COLUMN tls_enabled              INTEGER NOT NULL DEFAULT 0;
ALTER TABLE cluster_config ADD COLUMN verify_hostname          INTEGER NOT NULL DEFAULT 1;

ALTER TABLE cluster_config ADD COLUMN ssl_client_cert_cipher   BYTEA;
ALTER TABLE cluster_config ADD COLUMN ssl_client_key_cipher    BYTEA;
ALTER TABLE cluster_config ADD COLUMN ssl_key_password_cipher  BYTEA;
ALTER TABLE cluster_config ADD COLUMN ssl_trust_certs_cipher   BYTEA;

ALTER TABLE cluster_config ADD COLUMN oauth_token_url          TEXT;
ALTER TABLE cluster_config ADD COLUMN oauth_client_id          TEXT;
ALTER TABLE cluster_config ADD COLUMN oauth_client_secret_cipher BYTEA;
ALTER TABLE cluster_config ADD COLUMN oauth_scope              TEXT;

ALTER TABLE cluster_config ADD COLUMN custom_jaas_cipher       BYTEA;
ALTER TABLE cluster_config ADD COLUMN custom_props_cipher      BYTEA;

UPDATE cluster_config SET auth_type = CASE
    WHEN security_protocol IN ('SASL_PLAINTEXT', 'SASL_SSL') THEN 'PASSWORD'
    WHEN security_protocol = 'SSL' THEN 'MTLS'
    ELSE 'NONE'
END;

UPDATE cluster_config SET tls_enabled = CASE
    WHEN security_protocol IN ('SASL_SSL', 'SSL') THEN 1
    ELSE 0
END;
