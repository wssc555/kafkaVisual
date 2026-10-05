package com.example.kafkaviz.exception;

public class InvalidZkPathException extends RuntimeException {
    public InvalidZkPathException(String message) {
        super(message);
    }
}
