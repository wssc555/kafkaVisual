-- =====================================================================
-- V2 多认证方式扩展 —— SQLite 方言（默认数据源）
-- 由 SchemaInitializer 在 schema_version < 2 时执行（V1 之后）。
--
-- 幂等性口径：
--   SQLite 不支持 ALTER TABLE ... ADD COLUMN IF NOT EXISTS，因此本脚本
--   不做列级幂等判断，幂等完全由调用方的 schema_version 守卫保证
--   （已登记 v2 就不再执行）。中途崩溃会留下"部分列已加、版本未登记"
--   的状态，重跑将报 duplicate column name —— 按报错列名手工补齐即可。
--
-- 类型口径与 V1 一致：布尔列 INTEGER 0/1、时间/文本 TEXT、大对象 BLOB。
-- 所有新列都给 DEFAULT，因为 SQLite 不接受给既有表加"无默认值的 NOT NULL 列"。
-- 秘密材料列存 AES-GCM 密文（CryptoService），NULL/空 = 未配置。
-- =====================================================================

ALTER TABLE cluster_config ADD COLUMN auth_type                TEXT    NOT NULL DEFAULT 'NONE';
ALTER TABLE cluster_config ADD COLUMN tls_enabled              INTEGER NOT NULL DEFAULT 0;
ALTER TABLE cluster_config ADD COLUMN verify_hostname          INTEGER NOT NULL DEFAULT 1;

-- mTLS：客户端证书链 / 客户端私钥 / 私钥口令 / CA 证书（PEM 文本，加密落库）
ALTER TABLE cluster_config ADD COLUMN ssl_client_cert_cipher   BLOB;
ALTER TABLE cluster_config ADD COLUMN ssl_client_key_cipher    BLOB;
ALTER TABLE cluster_config ADD COLUMN ssl_key_password_cipher  BLOB;
ALTER TABLE cluster_config ADD COLUMN ssl_trust_certs_cipher   BLOB;

-- OAuth2 (SASL/OAUTHBEARER)：端点与 client id 需回显，故明文；
-- secret 是秘密材料，加密落库
ALTER TABLE cluster_config ADD COLUMN oauth_token_url          TEXT;
ALTER TABLE cluster_config ADD COLUMN oauth_client_id          TEXT;
ALTER TABLE cluster_config ADD COLUMN oauth_client_secret_cipher BLOB;
ALTER TABLE cluster_config ADD COLUMN oauth_scope              TEXT;

-- 逃生舱：完整 JAAS 串 + 附加客户端属性（键值对 JSON）。两者都可能内嵌口令，
-- 一律整体加密，不做"敏感键识别"
ALTER TABLE cluster_config ADD COLUMN custom_jaas_cipher       BLOB;
ALTER TABLE cluster_config ADD COLUMN custom_props_cipher      BLOB;

-- ---------------------------------------------------------------------
-- 回填：按既有 security_protocol 反推 auth_type / tls_enabled
-- （旧库只可能是''、SASL_PLAINTEXT、SASL_SSL 三种；SSL 作防御性归 MTLS）
-- ---------------------------------------------------------------------
UPDATE cluster_config SET auth_type = CASE
    WHEN security_protocol IN ('SASL_PLAINTEXT', 'SASL_SSL') THEN 'PASSWORD'
    WHEN security_protocol = 'SSL' THEN 'MTLS'
    ELSE 'NONE'
END;

UPDATE cluster_config SET tls_enabled = CASE
    WHEN security_protocol IN ('SASL_SSL', 'SSL') THEN 1
    ELSE 0
END;
