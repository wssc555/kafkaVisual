package com.example.kafkaviz.exception;

import org.apache.kafka.common.ConsumerGroupState;

/**
 * 消费组存在活跃成员,禁止重置 offset / 删除组。映射到 409/40902。
 */
public class GroupActiveException extends RuntimeException {

    public GroupActiveException(String group, ConsumerGroupState state) {
        super("Consumer group has active members, operation denied: group=" + group + ", state=" + state);
    }
}