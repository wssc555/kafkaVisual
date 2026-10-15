package com.example.kafkaviz.inspection.exception;

/**
 * 告警已被确认 → HTTP 409 / {@code 40904}。
 *
 * <p>幂等保护:重复 ack 会让"谁在什么时候认领了这条告警"变成最后一次的时间,
 * 而审计上更希望第一次的事实留下来 —— 因此直接拒绝第二次,而不是静默覆盖。
 */
public class AlreadyAcknowledgedException extends RuntimeException {

    private final long alertId;

    public AlreadyAcknowledgedException(long alertId) {
        super("Alert already acknowledged: " + alertId);
        this.alertId = alertId;
    }

    public long getAlertId() {
        return alertId;
    }
}
