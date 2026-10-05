package com.example.kafkaviz.kafka;

import org.apache.kafka.clients.consumer.KafkaConsumer;

/**
 * AutoCloseable 包装，确保异常时也能归还 Consumer。
 */
public class BorrowedConsumer implements AutoCloseable {

    private final KafkaConsumer<byte[], byte[]> consumer;
    private final ConsumerPool pool;
    private boolean released;

    public BorrowedConsumer(KafkaConsumer<byte[], byte[]> consumer, ConsumerPool pool) {
        this.consumer = consumer;
        this.pool = pool;
        this.released = false;
    }

    public KafkaConsumer<byte[], byte[]> get() {
        return consumer;
    }

    @Override
    public void close() {
        if (!released) {
            released = true;
            pool.release(consumer);
        }
    }
}