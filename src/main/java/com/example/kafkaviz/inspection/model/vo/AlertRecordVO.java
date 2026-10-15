package com.example.kafkaviz.inspection.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 告警列表项 / 详情(告警中心)。
 *
 * <p>{@code clusterId} 与 {@code clusterName} 让全局告警中心能按集群过滤与溯源 ——
 * 规则被删后仍能查到历史告警,靠的就是这行快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRecordVO {

    private long id;

    private long ruleId;

    /** 告警产生时的集群快照。 */
    private long clusterId;

    /** 集群显示名;集群已被删除时为 null。 */
    private String clusterName;

    private String scopeId;

    private String severity;

    /** {@code FIRING} / {@code RECOVERED};ack 不改变它。 */
    private String state;

    private String title;

    private String message;

    private String firedAt;

    private String recoveredAt;

    private String acknowledgedAt;

    private String acknowledgedBy;
}
