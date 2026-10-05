-- =====================================================================
-- V2 多认证方式扩展 —— MySQL 方言（外部库，用户手动配置连接）
-- 与 SQLite 版的差异：大对象 LONGBLOB、变长文本必须给显式长度
-- （MySQL 的 TEXT 不能有 DEFAULT，因此带默认值的列用 VARCHAR）。
-- 幂等性由 SchemaInitializer 的 schema_version 守卫保证：MySQL 8 不支持
-- ALTER TABLE ... ADD COLUMN IF NOT EXISTS，本脚本不做列级幂等判断。
-- =====================================================================

ALTER TABLE cluster_config ADD COLUMN auth_type                VARCHAR(16)  NOT NULL DEFAULT 'NONE';
ALTER TABLE cluster_config ADD COLUMN tls_enabled              INT          NOT NULL DEFAULT 0;
ALTER TABLE cluster_config ADD COLUMN verify_hostname          INT          NOT NULL DEFAULT 1;

ALTER TABLE cluster_config ADD COLUMN ssl_client_cert_cipher   LONGBLOB     NULL;
ALTER TABLE cluster_config ADD COLUMN ssl_client_key_cipher    LONGBLOB     NULL;
ALTER TABLE cluster_config ADD COLUMN ssl_key_password_cipher  LONGBLOB     NULL;
ALTER TABLE cluster_config ADD COLUMN ssl_trust_certs_cipher   LONGBLOB     NULL;

ALTER TABLE cluster_config ADD COLUMN oauth_token_url          VARCHAR(512) NULL;
ALTER TABLE cluster_config ADD COLUMN oauth_client_id          VARCHAR(256) NULL;
ALTER TABLE cluster_config ADD COLUMN oauth_client_secret_cipher LONGBLOB   NULL;
ALTER TABLE cluster_config ADD COLUMN oauth_scope              VARCHAR(256) NULL;

ALTER TABLE cluster_config ADD COLUMN custom_jaas_cipher       LONGBLOB     NULL;
ALTER TABLE cluster_config ADD COLUMN custom_props_cipher      LONGBLOB     NULL;

UPDATE cluster_config SET auth_type = CASE
    WHEN security_protocol IN ('SASL_PLAINTEXT', 'SASL_SSL') THEN 'PASSWORD'
    WHEN security_protocol = 'SSL' THEN 'MTLS'
    ELSE 'NONE'
END;

UPDATE cluster_config SET tls_enabled = CASE
    WHEN security_protocol IN ('SASL_SSL', 'SSL') THEN 1
    ELSE 0
END;
