package com.example.kafkaviz.inspection.model;

/**
 * {@code rule_state} 行 —— 状态去重的核心(唯一键 (rule_id, scope_id))。
 *
 * <p>只在状态转移时写行:持续 FIRING / 持续 OK 都只节流更新
 * {@code lastEvaluatedAt},不改 {@code currentState}。
 */
public record RuleStateRow(
        long id,
        long ruleId,
        String scopeId,
        String currentState,
        String lastEvaluatedAt,
        String lastFiredAt,
        String lastRecoveredAt) {

    public static final String OK = "OK";
    public static final String FIRING = "FIRING";

    public boolean firing() {
        return FIRING.equals(currentState);
    }
}
