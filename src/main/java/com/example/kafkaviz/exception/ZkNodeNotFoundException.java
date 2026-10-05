package com.example.kafkaviz.exception;

public class ZkNodeNotFoundException extends RuntimeException {
    public ZkNodeNotFoundException(String path) {
        super("ZK node not found: " + path);
    }
}