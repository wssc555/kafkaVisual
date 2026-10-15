package com.example.kafkaviz.inspection.exception;

/** 告警通道不存在 → HTTP 404 / {@code 40406}。 */
public class ChannelNotFoundException extends RuntimeException {

    private final long channelId;

    public ChannelNotFoundException(long channelId) {
        super("Alert channel not found: " + channelId);
        this.channelId = channelId;
    }

    public long getChannelId() {
        return channelId;
    }
}
