package com.example.kafkaviz.inspection.exception;

/**
 * 巡检规则定义非法 → HTTP 400 / {@code 40005}。
 *
 * <p>覆盖:cron 非法或低于最小间隔、指标键不在目录内、算子非法、窗口参数非法、
 * DSL 结构错、关联通道不存在、ZK scope 用于 KRaft 集群。
 */
public class InvalidRuleException extends RuntimeException {

    public InvalidRuleException(String message) {
        super(message);
    }

    public InvalidRuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
