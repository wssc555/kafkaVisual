package com.example.kafkaviz.inspection.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指标目录项({@code GET /api/c/{clusterId}/inspection/metrics})。
 *
 * <p>前端规则构建器的指标下拉直接渲染本列表 —— KRaft 集群拿不到 ZK 级指标
 * (后端按 {@code zkAvailable} 过滤),从源头杜绝无效配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetricCatalogItemVO {

    /** 指标键(规则 DSL 的 {@code metric} 字段取值)。 */
    private String key;

    /** {@code CLUSTER} / {@code TOPIC} / {@code CONSUMER_GROUP} / {@code ZK}。 */
    private String scope;

    /** {@code NUMBER} / {@code STRING} / {@code BOOL} —— 决定阈值比较方式。 */
    private String valueType;

    /** 单位(个/条/B);无量纲时为 null(JSON 中显式输出)。 */
    private String unit;

    private String description;
}
