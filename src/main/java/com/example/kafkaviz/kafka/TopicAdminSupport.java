package com.example.kafkaviz.kafka;

import com.example.kafkaviz.exception.TopicNotFoundException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Topic 存在性校验的统一收口。
 *
 * <p>不建议"先把全集群 topic 名称集合拉回来再 {@code contains}"的校验方式——topic 数达
 * 数千时每次校验都是一次全量 Metadata 抓取,且校验后往往紧接着又
 * {@code describeTopics(同一 topic)},等于对同一 topic 连发两次相关 RPC
 * ({@code POST /api/messages} 这种高频写路径按次重复支付)。
 *
 * <p>根因是 {@code describeTopics} 对不存在的 topic 抛 {@link UnknownTopicOrPartitionException},
 * 直接用需要额外异常映射。本类把这个映射做一次,调用方即可安全地"一次 describeTopics
 * 同时完成存在性校验 + 取描述"。
 *
 * <p>{@code describeTopics} 不会触发 topic 自动创建(只有 metadata fetch / produce 会),
 * 因此对开启 {@code auto.create.topics.enable} 的集群行为安全。保持静态、无状态。
 */
public final class TopicAdminSupport {

    private TopicAdminSupport() {
    }

    /**
     * 取回 topic 描述;不存在抛 {@link TopicNotFoundException}(40401)。
     *
     * @param timeoutMs Admin 调用阻塞上限,见 kafka.admin.default-api-timeout-ms
     */
    public static TopicDescription describeTopicOrThrow(AdminClient admin, String topic, long timeoutMs)
            throws ExecutionException, InterruptedException {
        try {
            // 用 topicNameValues():单 topic 场景与 all() 等价,且能直接拿到描述,
            // 免去调用方再 map.get(topic) 一次。
            return KafkaFutures.await(
                    admin.describeTopics(List.of(topic)).topicNameValues().get(topic),
                    timeoutMs, "describeTopics:" + topic);
        } catch (ExecutionException e) {
            // 先判 UnknownTopicOrPartition 再原样抛出:不能把 TopicNotFoundException
            // 包进 ExecutionException,否则会落 50001 而不是 40401。
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                throw new TopicNotFoundException(topic);
            }
            throw e;
        }
    }

    /** 仅校验存在性、不需要描述时使用。 */
    public static void assertTopicExists(AdminClient admin, String topic, long timeoutMs)
            throws ExecutionException, InterruptedException {
        describeTopicOrThrow(admin, topic, timeoutMs);
    }
}
