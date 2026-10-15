package com.example.kafkaviz.inspection.alert;

/**
 * 单通道发送结果。
 *
 * <p>{@code errorMessage} 在进入 {@code alert_dispatch_log} 之前还会被
 * {@code AlertDispatcher} 脱敏(去掉 URL 里的 query、截断到 1000 字符)——
 * 对端返回的错误体可能包含我们不认识也不该留存的内容。
 */
public record SendOutcome(boolean success, String errorMessage) {

    public static SendOutcome ok() {
        return new SendOutcome(true, null);
    }

    public static SendOutcome failed(String message) {
        return new SendOutcome(false, message);
    }
}
