package com.example.kafkaviz.inspection.model;

/**
 * {@code metric_sample} 行。
 *
 * <p>只有<b>带窗口</b>的条件会产生采样行(瞬时条件不落库),写入后由
 * {@code MetricSampleStore} 按 (rule_id, scope_id) 滚动删除到
 * {@code windowCount + buffer} 行。
 *
 * @param scopeId 集群级固定 {@code CLUSTER};Topic 级=topic 名;组级=group id;ZK 级=path
 */
public record MetricSample(
        long id,
        long ruleId,
        String metricKey,
        String scopeId,
        String value,
        String sampledAt) {
}
