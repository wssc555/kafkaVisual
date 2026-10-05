package com.example.kafkaviz.storage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 界面偏好 KV({@code ui_preference} 表)。
 *
 * <p>当前持久化的键只有 {@code activeClusterId} 与 {@code asideCollapsed},
 * 但表结构是通用的 KV,后续列宽之类的键可以直接加,不需要改后端。
 *
 * <p>upsert 语句收在 {@link StorageDialect#upsertPreferenceSql()}(SQLite/PG 走
 * {@code ON CONFLICT},MySQL 走 {@code ON DUPLICATE KEY UPDATE}),本类不出现方言痕迹。
 */
@Component
public class PreferenceStore {

    private static final String TABLE = "ui_preference";

    private final JdbcTemplate jdbcTemplate;
    private final StorageDialect dialect;

    public PreferenceStore(JdbcTemplate jdbcTemplate, StorageDialect dialect) {
        this.jdbcTemplate = jdbcTemplate;
        this.dialect = dialect;
    }

    /** 读单个键;不存在返回 null。 */
    public String get(String key) {
        var rows = jdbcTemplate.query(
                "SELECT " + q("value") + " FROM " + q(TABLE) + " WHERE " + q("key") + " = ?",
                (rs, rowNum) -> rs.getString(1), key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 全量读取(前端启动时一次拉回,避免逐键请求)。 */
    public Map<String, String> getAll() {
        Map<String, String> out = new LinkedHashMap<>();
        // 显式转型为 RowCallbackHandler:否则与 query(String, RowMapper) 的重载解析
        // 依赖 lambda 的 void 兼容性推断,可读性与稳定性都不如写清楚
        jdbcTemplate.query("SELECT " + q("key") + ", " + q("value") + " FROM " + q(TABLE),
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        out.put(rs.getString(1), rs.getString(2)));
        return out;
    }

    /**
     * 写入(幂等 upsert)。
     *
     * @throws IllegalArgumentException key 为空
     */
    public void put(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        jdbcTemplate.update(dialect.upsertPreferenceSql(), key.trim(), value == null ? "" : value);
    }

    /** {@code key} 是 MySQL 保留字,所有引用都要经方言语义。 */
    private String q(String identifier) {
        return dialect.quoteIdent(identifier);
    }
}
