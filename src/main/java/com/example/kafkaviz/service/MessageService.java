package com.example.kafkaviz.service;

import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.exception.ServiceUnavailableException;
import com.example.kafkaviz.exception.TopicNotFoundException;
import com.example.kafkaviz.kafka.BorrowedConsumer;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.TopicAdminSupport;
import com.example.kafkaviz.model.dto.OffsetForTimesRequest;
import com.example.kafkaviz.model.dto.ProduceMessageRequest;
import com.example.kafkaviz.model.dto.QueryMessageRequest;
import com.example.kafkaviz.model.vo.MessageQueryResult;
import com.example.kafkaviz.model.vo.MessageRecord;
import com.example.kafkaviz.model.vo.ProduceResult;
import com.example.kafkaviz.model.vo.TopicOffsetLookup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 消息查询与生产。ConsumerPool / Producer / AdminClient 均取自
 * {@link ClusterConnection} —— <b>每个集群有自己的消费者池与生产者</b>,
 * 池耗尽的 50301 也是按集群独立的。
 */
@Service
public class MessageService {

    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;
    /** Admin 调用的阻塞上限,见 kafka.admin.default-api-timeout-ms。 */
    private final long timeoutMs;

    public MessageService(KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
    }

    /**
     * 消息拉取算法：offset + count 纯拉取。
     *
     * @throws IllegalArgumentException count 超出上限 / offset 已越界(被 retention 清理或
     *                                  compact 跳过),后者 msg 会带上该分区当前最早位点
     */
    public MessageQueryResult query(ClusterConnection connection, QueryMessageRequest req) {
        int count = req.getCount();
        int maxCount = kafkaProperties.getConsumer().getQueryMaxCount();
        if (count > maxCount) {
            throw new IllegalArgumentException("count must not exceed " + maxCount);
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }

        TopicPartition tp = new TopicPartition(req.getTopic(), req.getPartition());
        long pollTimeout = kafkaProperties.getConsumer().getPollTimeoutMs();
        // 是否格式化在循环外算一次:列表默认不格式化(格式化只在消息详情抽屉用到)
        boolean needFormat = Boolean.TRUE.equals(req.getIncludeFormatted());

        List<MessageRecord> records = new ArrayList<>();
        long topicEndOffset;

        try (BorrowedConsumer borrowed = new BorrowedConsumer(
                connection.getConsumerPool().borrow(), connection.getConsumerPool())) {
            KafkaConsumer<byte[], byte[]> consumer = borrowed.get();
            consumer.assign(List.of(tp));

            // 获取 endOffset
            topicEndOffset = consumer.endOffsets(List.of(tp)).get(tp);

            long startOffset = req.getOffset();
            if (startOffset >= topicEndOffset) {
                // 空结果
                return buildResult(req, startOffset, records, topicEndOffset);
            }

            consumer.seek(tp, startOffset);

            // 已读到的最大 offset:追平 topicEndOffset 即退出,不必再空转一轮 poll 等满 pollTimeout
            // (翻到分区最后一页时原先固定多 ~1s 尾延迟)
            long lastReadOffset = startOffset - 1;
            while (records.size() < count) {
                if (lastReadOffset + 1 >= topicEndOffset) {
                    break;
                }
                ConsumerRecords<byte[], byte[]> polled;
                try {
                    polled = consumer.poll(Duration.ofMillis(pollTimeout));
                } catch (OffsetOutOfRangeException e) {
                    // 消费者是 auto.offset.reset=none(见 ClusterConnectionFactory.createConsumer):
                    // seek 到已被 retention 清理 / 被 compact 跳过的 offset 时,poll 直接抛异常,
                    // 而不是返回空 —— 下面的 polled.isEmpty() 兜底根本走不到,原实现会经
                    // handleKafka 变成不可理解的 50001。这里转成 40001(客户端参数问题),
                    // msg 带上该分区当前最早位点,引导用户从 earliest 重新查询。
                    long earliest = consumer.beginningOffsets(List.of(tp)).getOrDefault(tp, -1L);
                    throw new IllegalArgumentException(
                            "Offset " + startOffset + " is out of range for " + tp
                                    + ", earliest available offset is " + earliest);
                }
                if (polled.isEmpty()) {
                    break; // 兜底:seek 位置的数据已被 compact 删除时,首次 poll 即为空
                }
                for (ConsumerRecord<byte[], byte[]> record : polled) {
                    records.add(toMessageRecord(record, needFormat));
                    // 取 max:offset 在单次 poll 内递增,此处防御异常顺序导致提前退出
                    lastReadOffset = Math.max(lastReadOffset, record.offset());
                    if (records.size() >= count) {
                        break;
                    }
                }
            }
        }

        return buildResult(req, req.getOffset(), records, topicEndOffset);
    }

    /**
     * 查询各分区中"时间戳 ≥ 目标值"的首条消息 offset。
     *
     * <p>前端先调本方法定位 offset,再复用 {@link #query} 按 offset 拉取,
     * 实现"从某时间点开始看消息"。
     */
    public TopicOffsetLookup offsetsForTimes(ClusterConnection connection, OffsetForTimesRequest req)
            throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();
        String topic = req.getTopic();

        // 不存在的 topic 调 offsetsForTimes 不报错、静默返回空 —— 必须显式校验。
        // 校验与"取分区数"合并为一次 describeTopics(不做全量 listTopics + contains)。
        int partitionCount = TopicAdminSupport.describeTopicOrThrow(admin, topic, timeoutMs)
                .partitions().size();

        List<TopicPartition> tps;
        if (req.getPartition() != null) {
            if (req.getPartition() >= partitionCount) {
                throw new IllegalArgumentException("Partition does not exist: " + req.getPartition());
            }
            tps = List.of(new TopicPartition(topic, req.getPartition()));
        } else {
            tps = IntStream.range(0, partitionCount)
                    .mapToObj(p -> new TopicPartition(topic, p))
                    .toList();
        }

        Map<TopicPartition, Long> query = tps.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> req.getTimestamp()));

        // offsetsForTimes 不需要 assign(),直接传 TopicPartition 集合即可
        Map<TopicPartition, OffsetAndTimestamp> result;
        try (BorrowedConsumer borrowed = new BorrowedConsumer(
                connection.getConsumerPool().borrow(), connection.getConsumerPool())) {
            result = borrowed.get().offsetsForTimes(query);
        }

        List<TopicOffsetLookup.PartitionOffset> list = new ArrayList<>();
        for (TopicPartition tp : tps) {
            OffsetAndTimestamp oat = result.get(tp);
            list.add(TopicOffsetLookup.PartitionOffset.builder()
                    .partition(tp.partition())
                    .offset(oat == null ? -1L : oat.offset())
                    .matchTimestamp(oat == null ? null : oat.timestamp())
                    .build());
        }
        return TopicOffsetLookup.builder()
                .topic(topic)
                .timestamp(req.getTimestamp())
                .partitions(list)
                .build();
    }

    /**
     * 向指定 topic 同步发送单条消息,等待 broker ack 后返回写入位置。
     *
     * @throws TopicNotFoundException topic 不存在(broker 开启 auto.create.topics.enable 时
     *                                 直接 send 会静默创建该 topic,必须显式校验)
     * @throws org.apache.kafka.common.errors.RecordTooLargeException 消息超过 broker/客户端限制
     */
    public ProduceResult produce(ClusterConnection connection, ProduceMessageRequest req) {
        AdminClient admin = connection.getAdminClient();
        try {
            // describeTopics 不会触发自动创建,还省掉一次全集群 Metadata 抓取 ——
            // 这在高频写路径上按次重复支付。
            TopicAdminSupport.assertTopicExists(admin, req.getTopic(), timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted while checking topic existence", e);
        } catch (ExecutionException e) {
            throw new KafkaException("Topic check failed: " + e.getMessage(), e);
        }

        List<Header> headerList = new ArrayList<>();
        if (req.getHeaders() != null) {
            req.getHeaders().forEach((k, v) ->
                    headerList.add(new RecordHeader(k, v == null ? null : v.getBytes(StandardCharsets.UTF_8))));
        }

        ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                req.getTopic(),
                req.getPartition(),
                req.getTimestamp(),
                req.getKey() == null ? null : req.getKey().getBytes(StandardCharsets.UTF_8),
                req.getValue().getBytes(StandardCharsets.UTF_8),
                headerList);

        RecordMetadata meta;
        try {
            meta = connection.getProducer().send(record)
                    .get(kafkaProperties.getProducer().getSendTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Interrupted while producing message", e);
        } catch (TimeoutException e) {
            throw new KafkaException("Produce failed: " + e.getMessage(), e);
        } catch (ExecutionException e) {
            // 解包真实 cause:RecordTooLargeException 等客户端可修复的 Kafka 异常必须透传,
            // GlobalExceptionHandler 才有机会映射 400/40001;
            // 若直接包成 KafkaException,类型信息被抹掉,会落 50001。
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new KafkaException("Produce failed: " + e.getMessage(), e);
        }

        return ProduceResult.builder()
                .topic(meta.topic())
                .partition(meta.partition())
                .offset(meta.offset())
                .timestamp(meta.timestamp())
                .build();
    }

    private MessageQueryResult buildResult(QueryMessageRequest req, long startOffset,
                                           List<MessageRecord> records, long topicEndOffset) {
        boolean hasMore = (req.getOffset() + req.getCount()) < topicEndOffset;
        return MessageQueryResult.builder()
                .topic(req.getTopic())
                .partition(req.getPartition())
                .startOffset(startOffset)
                .records(records)
                .totalReturned(records.size())
                .endOffset(topicEndOffset)
                .hasMore(hasMore)
                .build();
    }

    /**
     * 单条消息转 VO。
     *
     * <p>{@code valueFormatted} 按需计算:只对调用方要求格式化的消息做 JSON 探测 + 美化,
     * 格式化实际只在消息详情抽屉里用到。
     *
     * @param needFormat 调用方是否要求格式化;false 时 valueFormatted 为 null,
     *                   该字段被 {@code @JsonInclude(NON_NULL)} 从 JSON 中省略
     */
    private MessageRecord toMessageRecord(ConsumerRecord<byte[], byte[]> record, boolean needFormat) {
        byte[] rawBytes = record.value();
        String key = record.key() != null ? new String(record.key(), StandardCharsets.UTF_8) : null;
        String rawValue = rawBytes != null ? new String(rawBytes, StandardCharsets.UTF_8) : null;

        String valueFormatted = null;
        // 超过阈值的 value 即使被要求格式化也跳过,避免 CPU 与响应体被单条大消息拖垮。
        // 按 UTF-8 字节数判断(而非 String.length() 的字符数):配置键名是 max-format-bytes,
        // 中文等多字节字符按字符数会低估 3~4 倍,阈值失去意义。
        // 该分支仅在 includeFormatted=true 时进入(列表默认不格式化),编码开销可接受。
        int maxFormatBytes = kafkaProperties.getConsumer().getMaxFormatBytes();
        if (needFormat && rawBytes != null && rawBytes.length <= maxFormatBytes) {
            try {
                Object obj = objectMapper.readValue(rawValue, Object.class);
                valueFormatted = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
            } catch (Exception e) {
                // 非 JSON，原样返回
                valueFormatted = rawValue;
            }
        }

        // headers 转换
        Map<String, String> headers = new LinkedHashMap<>();
        if (record.headers() != null) {
            for (Header header : record.headers()) {
                String val = header.value() != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
                headers.put(header.key(), val);
            }
        }

        return MessageRecord.builder()
                .topic(record.topic())
                .partition(record.partition())
                .offset(record.offset())
                .timestamp(record.timestamp())
                .timestampType(record.timestampType().name())
                .key(key)
                .value(rawValue)
                .valueFormatted(valueFormatted)
                .headers(headers)
                .build();
    }
}
