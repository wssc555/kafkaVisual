package com.example.kafkaviz.exception;

public class ConsumerGroupNotFoundException extends RuntimeException {
    public ConsumerGroupNotFoundException(String group) {
        super("Consumer group not found or expired: " + group);
    }
}