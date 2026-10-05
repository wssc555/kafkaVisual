package com.example.kafkaviz.service;

import com.example.kafkaviz.model.dto.StorageConfigRequest;
import com.example.kafkaviz.model.dto.StorageTestResult;
import com.example.kafkaviz.model.vo.StorageInfo;
import com.example.kafkaviz.storage.AppPaths;
import com.example.kafkaviz.storage.StorageConfig;
import com.example.kafkaviz.storage.StorageConfigFile;
import com.example.kafkaviz.storage.StorageDataSourceConfig;
import com.example.kafkaviz.storage.StorageDialect;
import com.example.kafkaviz.storage.StorageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 数据源配置服务({@code /api/storage} 三端点)。
 *
 * <p>关键口径:<b>保存不等于生效</b>。类型存在 {@code storage.json},而装配发生在启动期,
 * 所以保存后一律提示"重启后生效";{@link StorageInfo#getActiveType()} 用于把
 * "已保存未生效"这个状态如实暴露给前端。
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    /** 候选配置建连的超时(秒)。用 DriverManager.setLoginTimeout 通用兜底。 */
    private static final int TEST_LOGIN_TIMEOUT_SECONDS = 5;

    private final StorageConfigFile configFile;
    private final StorageDialect activeDialect;
    private final AppPaths appPaths;

    public StorageService(StorageConfigFile configFile, StorageDialect activeDialect, AppPaths appPaths) {
        this.configFile = configFile;
        this.activeDialect = activeDialect;
        this.appPaths = appPaths;
    }

    private static String mask(String passwordCipher) {
        return (passwordCipher == null || passwordCipher.isEmpty())
                ? "" : StorageInfo.PASSWORD_MASK;
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String summarize(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        // 驱动异常消息可能很长(含整个堆栈的 SQLState 描述),截断避免响应体臃肿
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }

    /** {@code GET /api/storage} —— 回显当前文件配置(口令打码)。 */
    public StorageInfo getInfo() {
        StorageConfig config = configFile.read();
        String activeType = activeDialect.type().configValue();
        String fileType = config.getType().configValue();
        return StorageInfo.builder()
                .type(fileType)
                .host(config.getHost())
                .port(config.getPort())
                .database(config.getDatabase())
                .username(config.getUsername())
                .password(mask(config.getPasswordCipher()))
                .extraParams(config.getExtraParams())
                .activeType(activeType)
                .restartRequired(!fileType.equals(activeType))
                .configFile(configFile.fileName())
                .build();
    }

    /**
     * {@code PUT /api/storage} —— 保存新配置,<b>不热切换</b>。
     *
     * <p><b>保存前强制 test 通过</b>(服务端强制,不依赖前端门控):
     * 对将要保存的同一份配置先做一次真实建连,失败则整个保存被拒绝(不落盘),
     * 用户在重启前就能发现坏配置,而不是重启后应用"配置丢了"般地回退 SQLite。
     * type=sqlite 时 test 是本地建连,开销可忽略。
     *
     * <p>幂等:同样的请求体重复提交结果一致(直接覆盖同一份文件)。
     *
     * @return 提示文案(含"需重启生效")
     * @throws IllegalArgumentException test 未通过(→ 40001,msg 携带连接失败摘要)
     */
    public String save(StorageConfigRequest req) {
        StorageConfig existing = configFile.read();
        StorageConfig target = toConfig(req);
        applyPassword(target, req, existing);

        StorageTestResult result = testConfig(target);
        if (!result.isSuccess()) {
            throw new IllegalArgumentException(
                    "Connection test failed, configuration was NOT saved: " + result.getError());
        }

        configFile.write(target);
        boolean typeChanged = target.getType() != activeDialect.type();
        return typeChanged
                ? "Storage configuration saved. Restart the application to take effect "
                  + "(current backend: " + activeDialect.type().configValue() + ")."
                : "Storage configuration saved. Connection settings changes take effect after restart.";
    }

    // ---------------------------------------------------------------
    // 参数校验与映射
    // ---------------------------------------------------------------

    /**
     * {@code POST /api/storage/test} —— 对<b>请求体里的候选配置</b>尝试建连,不落盘。
     *
     * <p>失败不抛 500:返回 {@code success=false} + error 摘要(测试失败是正常业务结果)。
     */
    public StorageTestResult test(StorageConfigRequest req) {
        // 参数问题(未知类型、缺 host 等)由 toConfig 抛 IllegalArgumentException → 40001,
        // 这里不捕获:那是调用方的输入错误,不是"连接失败"
        StorageConfig target = toConfig(req);
        applyPassword(target, req, configFile.read());
        return testConfig(target);
    }

    /**
     * 对一份<b>已解密就绪</b>的配置执行真实建连测试。
     *
     * <p>{@code synchronized}:{@code DriverManager.setLoginTimeout} 是 JVM 全局态,
     * 并发 test 会互相覆盖超时值(finally 恢复无法消除"设置→覆盖→读取"的交错)。
     * 桌面单用户场景下串行化 test 调用即可彻底消除,代价可忽略(PG/MySQL test
     * 最坏 5s 登录超时,由 {@link #TEST_LOGIN_TIMEOUT_SECONDS} 钳制)。
     */
    private synchronized StorageTestResult testConfig(StorageConfig target) {
        StorageDialect dialect = StorageDataSourceConfig.dialectFor(target.getType(), appPaths);
        String url = dialect.jdbcUrl(target);
        String password = configFile.decryptPassword(target.getPasswordCipher());

        if (target.getType() == StorageType.SQLITE) {
            return testSqlite(url);
        }
        return testViaDriverManager(url, target.getUsername(), password);
    }

    /**
     * SQLite 分支:仍然做一次真实建连 + {@code SELECT sqlite_version()}。
     *
     * <p>比"只判 app-data 目录可写"更有信息量 —— 目录不可写、路径被占用、
     * 磁盘满都会在建连时暴露;健康环境下恒 {@code success=true}(无凭据可填错)。
     */
    private StorageTestResult testSqlite(String url) {
        long start = System.nanoTime();
        try (Connection ignored = DriverManager.getConnection(url)) {
            long latency = elapsedMs(start);
            // 目录可写性顺带确认:SQLite 建连成功即说明能创建/写文件
            return StorageTestResult.builder()
                    .success(true)
                    .latencyMs(latency)
                    .version("SQLite (embedded, " + appPaths.describe() + ")")
                    .build();
        } catch (Exception e) {
            return StorageTestResult.builder()
                    .success(false)
                    .latencyMs(elapsedMs(start))
                    .error(summarize(e))
                    .build();
        }
    }

    /** PG / MySQL 分支:{@code DriverManager.getConnection} + {@code SELECT 1} + 产品版本。 */
    private StorageTestResult testViaDriverManager(String url, String username, String password) {
        int previousTimeout = DriverManager.getLoginTimeout();
        long start = System.nanoTime();
        try {
            DriverManager.setLoginTimeout(TEST_LOGIN_TIMEOUT_SECONDS);
            try (Connection conn = DriverManager.getConnection(url, username, password)) {
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                    rs.next();
                }
                DatabaseMetaData meta = conn.getMetaData();
                return StorageTestResult.builder()
                        .success(true)
                        .latencyMs(elapsedMs(start))
                        .version(meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion())
                        .build();
            }
        } catch (Exception e) {
            log.info("Storage connection test failed for {}: {}", url, e.getMessage());
            return StorageTestResult.builder()
                    .success(false)
                    .latencyMs(elapsedMs(start))
                    .error(summarize(e))
                    .build();
        } finally {
            DriverManager.setLoginTimeout(previousTimeout);
        }
    }

    /** 请求体 → StorageConfig(不含口令,由 {@link #applyPassword} 单独处理)。 */
    private StorageConfig toConfig(StorageConfigRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (req.getType() == null || req.getType().isBlank()) {
            throw new IllegalArgumentException("type is required (sqlite / postgresql / mysql)");
        }
        StorageType type = StorageType.fromString(req.getType());
        StorageConfig config = new StorageConfig();
        config.setType(type);
        config.setExtraParams(req.getExtraParams() == null ? "" : req.getExtraParams().trim());

        if (type == StorageType.SQLITE) {
            // type=sqlite 时外部库字段全部忽略(库文件位置由 --app.db-path 决定)
            return config;
        }

        String host = trimmed(req.getHost());
        if (host == null) {
            throw new IllegalArgumentException("host is required for " + type.configValue());
        }
        String database = trimmed(req.getDatabase());
        if (database == null) {
            throw new IllegalArgumentException("database is required for " + type.configValue());
        }
        String username = trimmed(req.getUsername());
        if (username == null) {
            throw new IllegalArgumentException("username is required for " + type.configValue());
        }
        config.setHost(host);
        config.setDatabase(database);
        config.setUsername(username);

        StorageDialect dialect = StorageDataSourceConfig.dialectFor(type, appPaths);
        int port = (req.getPort() == null || req.getPort() <= 0) ? dialect.defaultPort() : req.getPort();
        if (port > 65535) {
            throw new IllegalArgumentException("port must be <= 65535 but was " + port);
        }
        config.setPort(port);
        return config;
    }

    /**
     * 口令三态:null / 打码哨兵 → 保留原密文;空串 → 清空;其他 → 加密。
     *
     * <p>保留原值这一条很关键:GET 只回显 {@code ******},若把"提交上来的 {
     * @code ******} 当成真口令落盘,用户下次启动就会拿着字面量 ****** 去连库。
     */
    private void applyPassword(StorageConfig target, StorageConfigRequest req, StorageConfig existing) {
        String submitted = req.getPassword();
        if (submitted == null || StorageInfo.PASSWORD_MASK.equals(submitted)) {
            target.setPasswordCipher(existing.getPasswordCipher() == null ? "" : existing.getPasswordCipher());
            return;
        }
        if (submitted.isEmpty()) {
            target.setPasswordCipher("");
            return;
        }
        target.setPasswordCipher(configFile.encryptPassword(submitted));
    }
}
