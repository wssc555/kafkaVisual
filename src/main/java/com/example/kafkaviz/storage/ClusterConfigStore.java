package com.example.kafkaviz.storage;

import com.example.kafkaviz.exception.ClusterNotFoundException;
import com.example.kafkaviz.kafka.AuthSpec;
import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.model.dto.ClusterUpsertRequest;
import com.example.kafkaviz.model.vo.StorageInfo;
import com.example.kafkaviz.security.CryptoService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * {@code cluster_config} 的 CRUD 访问层。
 *
 * <p>职责边界:只碰这张表 + 凭据加解密 + 名称唯一校验。<b>不</b>涉及连接管理
 * (那是 {@link com.example.kafkaviz.kafka.ClusterConnectionManager})、
 * 也不碰任何 Controller/Service。
 *
 * <p>布尔列按 0/1 int 读写(三方言统一口径);时间列是 ISO-8601 文本;
 * 标识符统一经 {@link StorageDialect#quoteIdent(String)} 引用。
 *
 * <p><b>两点结构约定</b>:
 * <ol>
 *   <li><b>写入列顺序集中一处</b>:{@link #WRITABLE_COLUMNS} + {@link #bindWritableColumns}
 *       是唯一顺序事实源,INSERT 与 UPDATE 共用 —— 列数从 11 涨到 24 之后,分散绑定
 *       极易出现"列序与参数错位",故刻意收口;</li>
 *   <li><b>秘密字段统一三态</b>:{@link #resolveSecret(String, byte[])} 对
 *       password / 证书 / 私钥 / 私钥口令 / clientSecret / JAAS / 附加属性 八类
 *       秘密材料复用同一套语义,不再逐字段手写分支。</li>
 * </ol>
 */
@Component
public class ClusterConfigStore {

    private static final String COL_PASSWORD = "password_cipher";

    private static final String COL_SSL_CLIENT_CERT = "ssl_client_cert_cipher";

    private static final String COL_SSL_CLIENT_KEY = "ssl_client_key_cipher";

    private static final String COL_SSL_KEY_PASSWORD = "ssl_key_password_cipher";

    private static final String COL_SSL_TRUST_CERTS = "ssl_trust_certs_cipher";

    private static final String COL_OAUTH_CLIENT_SECRET = "oauth_client_secret_cipher";

    private static final String COL_CUSTOM_JAAS = "custom_jaas_cipher";

    private static final String COL_CUSTOM_PROPS = "custom_props_cipher";

    /** 所有密文列(update 时一次性读出用于三态判定)。 */
    private static final List<String> CIPHER_COLUMNS = List.of(
            COL_PASSWORD, COL_SSL_CLIENT_CERT, COL_SSL_CLIENT_KEY, COL_SSL_KEY_PASSWORD,
            COL_SSL_TRUST_CERTS, COL_OAUTH_CLIENT_SECRET, COL_CUSTOM_JAAS, COL_CUSTOM_PROPS);

    /**
     * 可写列(除 {@code id} / {@code created_at} / {@code updated_at} 之外的业务列)。
     *
     * <p><b>顺序即绑定顺序</b>:{@link #bindWritableColumns} 严格按本列表 setXxx,
     * INSERT / UPDATE 的 SQL 也从本列表拼装,三者天然不会错位。
     */
    private static final List<String> WRITABLE_COLUMNS = List.of(
            "name", "bootstrap_servers", "security_protocol", "sasl_mechanism", "username",
            COL_PASSWORD, "zk_connect_string", "enabled", "archive_enabled",
            "archive_retention_days", "sort_order",
            // ---- V2 新增 ----
            "auth_type", "tls_enabled", "verify_hostname",
            COL_SSL_CLIENT_CERT, COL_SSL_CLIENT_KEY, COL_SSL_KEY_PASSWORD, COL_SSL_TRUST_CERTS,
            "oauth_token_url", "oauth_client_id", COL_OAUTH_CLIENT_SECRET, "oauth_scope",
            COL_CUSTOM_JAAS, COL_CUSTOM_PROPS);

    /** 读取列(含主键与时间列);RowMapper 一律按列名取值,顺序仅影响 SELECT 列表写法。 */
    private static final List<String> READ_COLUMNS;
    /**
     * 附加属性(键值对)的 JSON 编解码。
     *
     * <p>用本类私有的 {@code ObjectMapper} 而不是注入 Spring 的 Bean:Store 只做
     * "string→string 映射"的朴素序列化,不依赖 Web 层的 Jackson 配置(命名策略、
     * 日期格式等),同时让单元测试可以直接 new 本类。
     */
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, String>> PROPS_TYPE = new TypeReference<>() {
    };

    static {
        List<String> read = new ArrayList<>();
        read.add("id");
        read.addAll(WRITABLE_COLUMNS);
        read.add("created_at");
        read.add("updated_at");
        READ_COLUMNS = List.copyOf(read);
    }

    private final JdbcTemplate jdbcTemplate;
    private final CryptoService cryptoService;
    private final StorageDialect dialect;

    public ClusterConfigStore(JdbcTemplate jdbcTemplate, CryptoService cryptoService, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.cryptoService = cryptoService;
        this.dialect = dialect;
    }

    // ---------------------------------------------------------------
    // 读
    // ---------------------------------------------------------------

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name.trim();
    }

    private static String requireServers(ClusterUpsertRequest req) {
        if (req.getBootstrapServers() == null || req.getBootstrapServers().isBlank()) {
            throw new IllegalArgumentException("bootstrapServers must not be blank");
        }
        return req.getBootstrapServers().trim();
    }

    private static void setNullableBytes(PreparedStatement ps, int index, byte[] value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BINARY);
        } else {
            ps.setBytes(index, value);
        }
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** 按 sort_order, id 排序(列表页稳定顺序)。 */
    public List<ClusterDefinition> list() {
        return jdbcTemplate.query("SELECT " + selectColumns() + " FROM " + table()
                + " ORDER BY " + q("sort_order") + ", " + q("id"), clusterRowMapper());
    }

    // ---------------------------------------------------------------
    // 写
    // ---------------------------------------------------------------

    public Optional<ClusterDefinition> findById(long id) {
        return jdbcTemplate.query("SELECT " + selectColumns() + " FROM " + table()
                + " WHERE " + q("id") + " = ?", clusterRowMapper(), id).stream().findFirst();
    }

    public Optional<ClusterDefinition> findByName(String name) {
        return jdbcTemplate.query("SELECT " + selectColumns() + " FROM " + table()
                + " WHERE " + q("name") + " = ?", clusterRowMapper(), name).stream().findFirst();
    }

    /** 供种子导入判断空表。 */
    public int count() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table(), Integer.class);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    /** 是否存在(供存在性校验复用)。 */
    public boolean exists(long id) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table() + " WHERE " + q("id") + " = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /**
     * 名称是否存在(轻量版,只做 COUNT,不做全行解密)。
     *
     * <p>名称重复校验只需要"有没有"这个事实,不需要完整定义 —— 用 {@link #findByName(name)}
     * 做存在性校验会把 8 个密文列一起拉回来并逐列解密,是不必要的开销。
     */
    public boolean existsByName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table() + " WHERE " + q("name") + " = ?", Integer.class,
                name.trim());
        return n != null && n > 0;
    }

    /**
     * 按名称取 id(轻量版,只查主键列,不做全行解密)。
     *
     * <p>用于 update 时判断"新名称是否与其它集群冲突":只需 id 就够了,
     * 不必为一次重复校验付出 8 列密文的解密开销。
     */
    public Optional<Long> findIdByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        List<Long> rows = jdbcTemplate.query(
                "SELECT " + q("id") + " FROM " + table() + " WHERE " + q("name") + " = ?",
                (rs, rowNum) -> rs.getLong(1), name.trim());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 新增;返回带自增 id 的完整定义。
     *
     * @throws IllegalArgumentException 名称已存在(msg 指明是哪个名称)
     */
    public ClusterDefinition insert(ClusterUpsertRequest req) {
        String name = requireName(req.getName());
        String servers = requireServers(req);
        // 轻量存在性校验:只做 COUNT,不做全行解密(见 existsByName)
        if (existsByName(name)) {
            throw new IllegalArgumentException("Cluster name already exists: " + name);
        }

        String now = now();
        String sql = "INSERT INTO " + table() + " (" + writableColumns(", ")
                + ", " + q("created_at") + ", " + q("updated_at") + ") VALUES ("
                + placeholders(WRITABLE_COLUMNS.size() + 2) + ")";

        // 新建时没有"库中现值",三态的"保持原值"分支退化为不写入;
        // 但请求里给出的秘密字段必须照常加密落库 —— 曾因误用 Secrets.empty() 把
        // 全部凭据丢掉,新建集群读回 credentialPresence 全 false,validate 带
        // clusterId 补全时也只能补到空串而误报 40001
        Secrets secrets = secretsForInsert(req);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                int next = bindWritableColumns(ps, req, secrets, 1);
                ps.setString(next++, now);
                ps.setString(next, now);
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            // 预检查之外的第二道防线:并发插入同名时由唯一约束兜底,同样映射 40001
            throw new IllegalArgumentException("Cluster name already exists: " + name);
        }

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert succeeded but no generated key returned for cluster " + name);
        }
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException(
                        "Cluster " + key.longValue() + " disappeared right after insert"));
    }

    /**
     * 全量更新(未出现在请求体里的可选字段按缺省值处理)。
     *
     * @throws ClusterNotFoundException 目标不存在 → 404/40404
     * @throws IllegalArgumentException 新名称与其他集群冲突 → 400/40001
     */
    public ClusterDefinition update(long id, ClusterUpsertRequest req) {
        requireExists(id);
        String name = requireName(req.getName());
        String servers = requireServers(req);
        // 轻量存在性校验:只查 id 列,不做全行解密(见 findIdByName)
        findIdByName(name).ifPresent(otherId -> {
            if (otherId != id) {
                throw new IllegalArgumentException("Cluster name already exists: " + name);
            }
        });

        String sql = "UPDATE " + table() + " SET " + writableColumns(" = ?, ")
                + " = ?, " + q("updated_at") + " = ? WHERE " + q("id") + " = ?";

        // 秘密字段三态:null / 打码哨兵 → 沿用库中原密文(前端"没改"的语义)
        Secrets secrets = secretsForUpdate(req, currentCiphers(id));

        try {
            jdbcTemplate.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(sql);
                int next = bindWritableColumns(ps, req, secrets, 1);
                ps.setString(next++, now());
                ps.setLong(next, id);
                return ps;
            });
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Cluster name already exists: " + name);
        }

        return findById(id).orElseThrow(() -> new IllegalStateException("Cluster " + id + " disappeared after update"));
    }

    /**
     * 删除配置行。
     *
     * @return true = 确实删掉了一行;false = 该 id 不存在(调用方自行决定是否 404)
     */
    public boolean delete(long id) {
        return jdbcTemplate.update("DELETE FROM " + table() + " WHERE " + q("id") + " = ?", id) > 0;
    }

    /**
     * 行 → {@link ClusterDefinition},各密文列即时解密为明文(仅内存)。
     *
     * <p>解密失败(secret.key 被换、行被改坏)会让本行读取抛异常 —— 这是刻意的:
     * 拿空口令去连只会得到误导性的"认证失败",不如让原因显式暴露。
     */
    private RowMapper<ClusterDefinition> clusterRowMapper() {
        return (rs, rowNum) -> new ClusterDefinition(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("bootstrap_servers"),
                rs.getString("security_protocol"),
                rs.getString("sasl_mechanism"),
                rs.getString("username"),
                decryptFromBytes(rs.getBytes(COL_PASSWORD)),
                new AuthSpec(
                        rs.getString("auth_type"),
                        rs.getInt("tls_enabled") != 0,
                        rs.getInt("verify_hostname") != 0,
                        decryptFromBytes(rs.getBytes(COL_SSL_CLIENT_CERT)),
                        decryptFromBytes(rs.getBytes(COL_SSL_CLIENT_KEY)),
                        decryptFromBytes(rs.getBytes(COL_SSL_KEY_PASSWORD)),
                        decryptFromBytes(rs.getBytes(COL_SSL_TRUST_CERTS)),
                        rs.getString("oauth_token_url"),
                        rs.getString("oauth_client_id"),
                        decryptFromBytes(rs.getBytes(COL_OAUTH_CLIENT_SECRET)),
                        rs.getString("oauth_scope"),
                        decryptFromBytes(rs.getBytes(COL_CUSTOM_JAAS)),
                        parseProps(rs.getBytes(COL_CUSTOM_PROPS))),
                rs.getString("zk_connect_string"),
                rs.getInt("enabled") != 0,
                rs.getInt("archive_enabled") != 0,
                rs.getInt("archive_retention_days"),
                rs.getInt("sort_order"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    /**
     * 按 {@link #WRITABLE_COLUMNS} 的顺序绑定全部业务列。
     *
     * <p>{@code security_protocol}/{@code sasl_mechanism} 在此处写入的是<b>派生值</b>
     * (见 {@code ClusterUpsertRequest#deriveSecurityProtocol}),即"物化派生列"的落库点;
     * {@code auth_type} 同时落库作为判别符。
     *
     * @param startIndex 首个参数位(INSERT / UPDATE 都是 1)
     * @return 下一个可用的参数位
     */
    private int bindWritableColumns(PreparedStatement ps, ClusterUpsertRequest req, Secrets secrets,
                                    int startIndex) throws SQLException {
        int i = startIndex;
        ps.setString(i++, requireName(req.getName()));
        ps.setString(i++, requireServers(req));
        ps.setString(i++, req.deriveSecurityProtocol());
        ps.setString(i++, req.deriveSaslMechanism());
        ps.setString(i++, req.usernameOrDefault());
        setNullableBytes(ps, i++, secrets.password());
        ps.setString(i++, req.zkConnectStringOrDefault());
        ps.setInt(i++, req.enabledOrDefault() ? 1 : 0);
        ps.setInt(i++, req.archiveEnabledOrDefault() ? 1 : 0);
        ps.setInt(i++, req.archiveRetentionDaysOrDefault());
        ps.setInt(i++, req.sortOrderOrDefault());
        ps.setString(i++, req.authTypeOrDefault());
        ps.setInt(i++, req.tlsEnabledOrDefault() ? 1 : 0);
        ps.setInt(i++, req.verifyHostnameOrDefault() ? 1 : 0);
        setNullableBytes(ps, i++, secrets.sslClientCert());
        setNullableBytes(ps, i++, secrets.sslClientKey());
        setNullableBytes(ps, i++, secrets.sslKeyPassword());
        setNullableBytes(ps, i++, secrets.sslTrustCerts());
        ps.setString(i++, req.oauthTokenUrlOrDefault());
        ps.setString(i++, req.oauthClientIdOrDefault());
        setNullableBytes(ps, i++, secrets.oauthClientSecret());
        ps.setString(i++, req.oauthScopeOrDefault());
        setNullableBytes(ps, i++, secrets.customJaas());
        setNullableBytes(ps, i, secrets.customProps());
        return i + 1;
    }

    /**
     * 新建路径:无库中现值(current 全传 null),三态退化为
     * "请求给了 → 加密落库;没给 / 空串 → 不写"。
     *
     * <p>与 {@link #secretsForUpdate} 共用 {@link #resolveSecret}/{@link #resolveProps}
     * 的同一套三态实现,保证 INSERT / UPDATE 语义一致。
     */
    private Secrets secretsForInsert(ClusterUpsertRequest req) {
        return new Secrets(
                resolveSecret(req.getPassword(), null),
                resolveSecret(req.getSslClientCertPem(), null),
                resolveSecret(req.getSslClientKeyPem(), null),
                resolveSecret(req.getSslClientKeyPassword(), null),
                resolveSecret(req.getSslTrustCertsPem(), null),
                resolveSecret(req.getOauthClientSecret(), null),
                resolveSecret(req.getCustomJaas(), null),
                resolveProps(req.getCustomProps(), null));
    }

    /** 更新路径:逐字段走三态(null / 打码哨兵 → 沿用库中密文)。 */
    private Secrets secretsForUpdate(ClusterUpsertRequest req, Map<String, byte[]> current) {
        return new Secrets(
                resolveSecret(req.getPassword(), current.get(COL_PASSWORD)),
                resolveSecret(req.getSslClientCertPem(), current.get(COL_SSL_CLIENT_CERT)),
                resolveSecret(req.getSslClientKeyPem(), current.get(COL_SSL_CLIENT_KEY)),
                resolveSecret(req.getSslClientKeyPassword(), current.get(COL_SSL_KEY_PASSWORD)),
                resolveSecret(req.getSslTrustCertsPem(), current.get(COL_SSL_TRUST_CERTS)),
                resolveSecret(req.getOauthClientSecret(), current.get(COL_OAUTH_CLIENT_SECRET)),
                resolveSecret(req.getCustomJaas(), current.get(COL_CUSTOM_JAAS)),
                resolveProps(req.getCustomProps(), current.get(COL_CUSTOM_PROPS)));
    }

    /**
     * 秘密字段三态的唯一实现。
     *
     * <p>对全部秘密材料(password / 客户端证书 / 客户端私钥 / 私钥口令 / CA /
     * clientSecret / JAAS)语义完全一致:
     * <ul>
     *   <li>{@code incoming == null} —— 保持库中现值(新建时为"无");</li>
     *   <li>{@code incoming == "******"} —— 同上(对打码哨兵的防御);</li>
     *   <li>空串 —— 显式清空(写 null);</li>
     *   <li>其他 —— 作为新值加密。</li>
     * </ul>
     *
     * @param current 库中当前密文(新建路径传 null)
     */
    private byte[] resolveSecret(String incoming, byte[] current) {
        if (incoming == null || StorageInfo.PASSWORD_MASK.equals(incoming)) {
            return current;
        }
        return encryptToBytes(incoming);
    }

    /**
     * 附加属性的三态:null → 保持原值;空映射 → 清空;非空 → 整体序列化加密。
     *
     * <p>打码哨兵语义在"结构化字段"上不适用(前端提交的是完整键值对或干脆不提交),
     * 因此这里不做哨兵判断 —— 与 {@code Map} 类型的可表达性一致。
     */
    private byte[] resolveProps(Map<String, String> incoming, byte[] current) {
        if (incoming == null) {
            return current;
        }
        if (incoming.isEmpty()) {
            return null;
        }
        return serializeProps(incoming);
    }

    /** 一次性读出全部密文列(避免 8 次单列查询)。 */
    private Map<String, byte[]> currentCiphers(long id) {
        String columns = CIPHER_COLUMNS.stream().map(this::q).collect(Collectors.joining(", "));
        List<Map<String, byte[]>> rows = jdbcTemplate.query(
                "SELECT " + columns + " FROM " + table() + " WHERE " + q("id") + " = ?",
                (rs, rowNum) -> {
                    Map<String, byte[]> row = new LinkedHashMap<>();
                    for (String column : CIPHER_COLUMNS) {
                        row.put(column, rs.getBytes(column));
                    }
                    return row;
                }, id);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private void requireExists(long id) {
        if (!exists(id)) {
            // 404/40404:配置不存在(与"名称重复"的 40001 语义严格区分)
            throw new ClusterNotFoundException(id);
        }
    }

    /**
     * 明文 → BLOB。
     *
     * <p>{@link CryptoService} 的契约是 String → Base64 String(便于写 JSON 配置),
     * 而本表列类型是 BLOB/BYTEA/LONGBLOB,因此在 Store 内做一层 Base64 编解码桥接:
     * 库里存的是<b>原始密文字节</b>,不是 Base64 文本。
     */
    private byte[] encryptToBytes(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        return Base64.getDecoder().decode(cryptoService.encrypt(plain));
    }

    /** BLOB → 明文;NULL / 空 → 空串(语义:未配置)。 */
    private String decryptFromBytes(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return "";
        }
        return cryptoService.decrypt(Base64.getEncoder().encodeToString(blob));
    }

    /** 附加属性:bytes → Map(读不出来即抛,不做静默降级)。 */
    private Map<String, String> parseProps(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return Map.of();
        }
        try {
            return JSON.readValue(blob, PROPS_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to read customProps (corrupted ciphertext or invalid JSON)", e);
        }
    }

    /** 附加属性:Map → bytes;空映射返回 null(调用方决定是否写)。 */
    private byte[] serializeProps(Map<String, String> props) {
        // 写入前复用 AuthSpec 的同一套清洗:保证"写入即净化",
        // 否则含 null 值的 map(非 REST 路径)会写进库、读回时又被静默丢弃
        Map<String, String> cleaned = AuthSpec.sanitizeProps(props);
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsBytes(cleaned);
        } catch (Exception e) {
            throw new IllegalArgumentException("customProps cannot be serialized: " + e.getMessage(), e);
        }
    }

    private String writableColumns(String separator) {
        return WRITABLE_COLUMNS.stream().map(this::q).collect(Collectors.joining(separator));
    }

    private String selectColumns() {
        return READ_COLUMNS.stream().map(this::q).collect(Collectors.joining(", "));
    }

    private String placeholders(int count) {
        return IntStream.range(0, count).mapToObj(n -> "?").collect(Collectors.joining(", "));
    }

    private String table() {
        return q("cluster_config");
    }

    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }

    /**
     * 一次 insert / update 要写入的 8 个密文列。
     *
     * <p>抽成小 record 的目的:让 {@link #bindWritableColumns} 的签名保持可读,
     * 而不是再挂 8 个 {@code byte[]} 参数(位置参数一多必然错位)。
     */
    private record Secrets(
            byte[] password,
            byte[] sslClientCert,
            byte[] sslClientKey,
            byte[] sslKeyPassword,
            byte[] sslTrustCerts,
            byte[] oauthClientSecret,
            byte[] customJaas,
            byte[] customProps) {
    }
}
