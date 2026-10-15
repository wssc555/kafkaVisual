package com.example.kafkaviz.inspection.alert;

import java.util.Map;

/**
 * 一次告警投递的内容(与具体通道无关)。
 *
 * <p>{@code alertId} 为 null 表示"不经规则的直发"(通道 test 端点)—— 那条路径
 * 按约定不写 {@code alert_record},各 sender 据此跳过"回写投递结果到告警行"的动作。
 *
 * @param metricValues 触发时刻的指标快照({@code metricKey → value}),进邮件正文与
 *                     webhook body,是排障时最有用的一段
 */
public record AlertMessage(
        Long alertId,
        Long ruleId,
        Long clusterId,
        String scopeId,
        String severity,
        String state,
        String title,
        String message,
        Map<String, String> metricValues) {

    /** 通道 test 用的固定内容消息。 */
    public static AlertMessage testMessage() {
        return new AlertMessage(null, null, null, "TEST", "INFO", "FIRING",
                "Kafka Visualizer 通道测试",
                "This is a test alert from Kafka Visualizer — if you see it, the channel works.",
                Map.of());
    }
}
