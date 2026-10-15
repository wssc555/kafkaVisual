package com.example.kafkaviz.inspection.exception;

/**
 * 名称重复 → HTTP 409 / {@code 40903}。
 *
 * <p>复用同一个异常覆盖两处唯一性:规则名<b>同集群内</b>唯一、通道名<b>全局</b>唯一。
 * 跨集群同名规则是合法的 —— 唯一键是 {@code (cluster_id, name)}。
 */
public class DuplicateNameException extends RuntimeException {

    public DuplicateNameException(String message) {
        super(message);
    }
}
