package com.example.kafkaviz.inspection.model;

/**
 * {@code alert_dispatch_log} 行 —— 每告警每通道一条发送结果。
 *
 * <p>独立建表(而不是在 alert_record 上加字段)的理由:一条告警可以走多个通道,
 * 各通道成败不同,必须逐通道可查。
 *
 * @param errorMessage 失败原因(脱敏后);成功时为 null
 */
public record AlertDispatchLog(
        long id,
        long alertId,
        long channelId,
        String status,
        String errorMessage,
        String createdAt) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
}
