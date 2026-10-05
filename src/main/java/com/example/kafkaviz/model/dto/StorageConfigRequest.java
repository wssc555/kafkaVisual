package com.example.kafkaviz.model.dto;

import lombok.Data;

/**
 * {@code PUT /api/storage} 与 {@code POST /api/storage/test} 的请求体。
 *
 * <p>{@code type} 用 String 而不是枚举:UI 的 radio 值是 {@code sqlite|postgresql|mysql},
 * Jackson 默认按枚举名精确匹配(且大小写敏感),直接绑枚举会让 {@code "sqlite"} 解析失败。
 * 这里统一走 {@code StorageType.fromString} 做宽容解析 + 明确报错(→ 40001)。
 *
 * <p>跨字段必填(host / database / username 仅在 PG/MySQL 下必填)无法用注解表达,
 * 由 {@code StorageService} 校验并抛 {@link IllegalArgumentException}(→ 40001)。
 */
@Data
public class StorageConfigRequest {

    /** 数据源类型:sqlite / postgresql / mysql。必填。 */
    private String type;

    private String host;

    /** 0 或缺省时用方言默认端口(PG 5432 / MySQL 3306)。 */
    private Integer port;

    private String database;

    private String username;

    /**
     * 明文口令。
     *
     * <p>三态语义(与 {@code StorageService} 的约定一致):
     * <ul>
     *   <li>{@code null} —— 保持库中已有口令不变(编辑时不回填明文,避免误清空);</li>
     *   <li>{@code "******"} —— 同上(GET 回显的打码值原样提交时不得被当成真口令);</li>
     *   <li>空串 —— 显式清空口令。</li>
     * </ul>
     */
    private String password;

    /** 追加到 JDBC URL 的高级参数,形如 {@code sslMode=REQUIRED}。 */
    private String extraParams;
}
