package com.example.kafkaviz.exception;

public class ZkDeleteRootException extends RuntimeException {
    public ZkDeleteRootException() {
        super("Root path '/' cannot be deleted");
    }
}