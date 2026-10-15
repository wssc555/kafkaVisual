package com.example.kafkaviz.inspection.exception;

/** 巡检规则不存在 → HTTP 404 / {@code 40405}。 */
public class RuleNotFoundException extends RuntimeException {

    private final long ruleId;

    public RuleNotFoundException(long ruleId) {
        super("Inspection rule not found: " + ruleId);
        this.ruleId = ruleId;
    }

    public long getRuleId() {
        return ruleId;
    }
}
