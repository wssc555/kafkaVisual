package com.example.kafkaviz.inspection.model;

/**
 * {@code alert_record} 行 —— 站内信即本表的视图(读即见)。
 *
 * <p>{@code clusterId} 是告警产生时的集群快照:规则可以随后被删掉,但历史告警
 * 必须还能按集群过滤,因此不能只靠 {@code rule_id} 反查。
 *
 * @param state {@code FIRING} / {@code RECOVERED};ack 不改变它
 */
public record AlertRecord(
        long id,
        long ruleId,
        long clusterId,
        String scopeId,
        String severity,
        String state,
        String title,
        String message,
        String firedAt,
        String recoveredAt,
        String acknowledgedAt,
        String acknowledgedBy) {

    public static final String FIRING = "FIRING";
    public static final String RECOVERED = "RECOVERED";
}
