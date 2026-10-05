package com.example.kafkaviz.storage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 可插拔存储层的后端类型。
 *
 * <p>切换存储后端必须<b>重启生效</b>:类型存在数据源之外的 {@code storage.json}
 * (自举问题,见 {@code StorageConfigFile}),启动时读一次即定。
 *
 * <p>JSON 绑定口径(读写对称,经 {@code storage.json} 与 {@code /api/storage} 双路验证):
 * <ul>
 *   <li>序列化({@code @JsonValue})统一输出<b>小写</b>规范名 —— 用户手改文件时看到
 *       的形态与直觉一致;</li>
 *   <li>反序列化({@code @JsonCreator})走 {@link #fromString} 宽容解析(大小写不敏感、
 *       去空白)—— 否则 Jackson 默认按枚举名精确匹配,手写 {@code "postgresql"}
 *       会被 {@code StorageConfigFile} 当解析失败静默回退 SQLite,表现为数据"消失"。</li>
 * </ul>
 */
public enum StorageType {

    /** 默认:零配置,库文件落在 app-data 目录下 {@code kafkaviz.db}。 */
    SQLITE,
    /** 外部 PostgreSQL,由用户在设置页手动填写连接信息。 */
    POSTGRESQL,
    /** 外部 MySQL,由用户在设置页手动填写连接信息。 */
    MYSQL;

    /**
     * 宽容解析:大小写不敏感、忽略首尾空白;null / 空白视为默认 SQLITE。
     *
     * <p>未知值抛 {@link IllegalArgumentException}(经 GlobalExceptionHandler 映射 40001),
     * <b>不</b>静默回退 SQLITE —— 用户写错类型时必须看到报错,否则会以为配置生效了,
     * 实际却在写另一个库。
     */
    @JsonCreator
    public static StorageType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return SQLITE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unsupported storage type: '" + raw + "' (expected one of sqlite / postgresql / mysql)");
        }
    }

    /** 落盘/序列化用的规范小写名(与 {@link #fromString} 构成读写对称)。 */
    @JsonValue
    public String configValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
