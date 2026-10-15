package com.example.kafkaviz.inspection.model;

/**
 * {@code inspection_rule} 行。
 *
 * <p>与库列一一对应(布尔按 int 读写、时间按 ISO-8601 文本),不含任何派生字段 ——
 * 派生的展示字段(conditionSummary / channelNames / lastState)在
 * {@code RuleListItemVO} 里组装。
 *
 * @param nextFireAt 下次触发时间(ISO-8601);null = 待对账(ticker 不会命中)
 */
public record InspectionRule(
        long id,
        long clusterId,
        String name,
        boolean enabled,
        String severity,
        String metricScope,
        String scopeFilter,
        String conditionJson,
        String cronExpr,
        String nextFireAt,
        String description,
        String createdAt,
        String updatedAt) {
}
