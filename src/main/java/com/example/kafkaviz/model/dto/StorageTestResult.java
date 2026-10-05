package com.example.kafkaviz.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * {@code POST /api/storage/test} 的结果。
 *
 * <p>测试失败是<b>正常业务结果</b>(用户就爱填错地址),因此走 200 + {@code code=0}
 * 并在 body 里带 {@code success=false} —— 不抛 5xx,也不新增错误码。
 */
@Data
@Builder
public class StorageTestResult {

    private boolean success;

    /** 建连 + SELECT 1 的总耗时(ms);失败时表示失败前耗时。 */
    private Long latencyMs;

    /** {@code DatabaseMetaData.getDatabaseProductVersion()} / SQLite 的库版本;失败时为 null。 */
    private String version;

    /** 失败摘要(驱动异常消息);成功时为 null。 */
    private String error;
}
