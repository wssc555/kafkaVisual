package com.example.kafkaviz.kafka;

/**
 * 消费者池耗尽异常，映射到 HTTP 503。
 */
public class ConsumerPoolExhaustedException extends RuntimeException {

    public ConsumerPoolExhaustedException(String message) {
        super(message);
    }
}