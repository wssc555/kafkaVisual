package com.example.kafkaviz.inspection.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 巡检概览(<b>跨集群聚合</b>)。
 *
 * <p>概览页不随集群切换:它的意义就是"所有集群加起来现在什么状态"。
 * {@code recentAlerts} 每条都带 {@code clusterId} 与集群名,发现异常后能直接溯源
 * 到具体集群。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InspectionOverviewVO {

    private long totalRules;

    private long enabledRules;

    /** 仍 FIRING(未恢复)的告警数。 */
    private long firingCount;

    /** 未读(FIRING 且未 ack)数,与 {@code /alerts/unread-count} 同口径。 */
    private long unreadCount;

    /** 当日 00:00 之后恢复的告警数。 */
    private long recoveredToday;

    /** 当前 FIRING 告警按严重度分桶。 */
    private Map<String, Integer> bySeverity;

    private List<AlertRecordVO> recentAlerts;
}
