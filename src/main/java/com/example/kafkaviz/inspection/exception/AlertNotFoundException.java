package com.example.kafkaviz.inspection.exception;

/** 告警不存在 → HTTP 404 / {@code 40407}。 */
public class AlertNotFoundException extends RuntimeException {

    private final long alertId;

    public AlertNotFoundException(long alertId) {
        super("Alert not found: " + alertId);
        this.alertId = alertId;
    }

    public long getAlertId() {
        return alertId;
    }
}
