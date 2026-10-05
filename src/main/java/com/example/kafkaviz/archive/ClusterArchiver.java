package com.example.kafkaviz.archive;

import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.ClusterConnectionFactory;
import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.kafka.KafkaFutures;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 单集群消息归档器。
 *
 * <p>形态:每个 {@code ClusterConnection} 内一个可选组件,{@code archive_enabled=1}
 * 且建连成功后启动,断开时优雅停止。它<b>不是</b> Spring Bean —— 生命周期跟着连接走。
 *
 * <p>双线程模型(poll 与落库解耦,消费速率不受 DB 抖动影响):
 * <pre>
 *   消费线程 archive-consumer-&lt;id&gt;：poll(1s) → 转 ArchivedMessage → 入有界队列(满则背压) → 批量 commitSync
 *   写线程   archive-writer-&lt;id&gt;  ：队列攒批(500 条 或 500ms) → 单事务幂等批量落 msg_&lt;clusterId&gt;
 * </pre>
 *
 * <p>几个实现上的选择,原因都写在对应位置:
 * <ul>
 *   <li>订阅用 <b>Pattern</b> 而不是全名列表:全名列表需要自己处理"topic 新增/删除"，
 *       而订阅了已删除的 topic 会让 poll 直接抛 UnknownTopicOrPartition。
 *       Pattern({@code ^(?!_)} 排除内部 topic)由客户端负责增量调整,失败面小得多。</li>
 *   <li>台账同步用<b>一次</b> {@code describeTopics} 取全部分区数,而不是每个 topic 一次 ——
 *       否则 500 个 topic 的集群每 10s 就是 500 次 RPC。</li>
 *   <li>写失败<b>原地重试</b>、不中断也不丢批:消费线程在入队后即提交 offset,
 *       失败批次若丢弃,该段消息就永久丢失(offset 已提交,不会重放)。批次保留
 *       在写线程内存里退避重试,DB 故障期间队列积压、消费侧背压自然消化故障窗口;
 *       只有停止时仍失败的余量才记 ERROR 放弃(见 {@link #flushQuietly})。</li>
 *   <li>归档线程的任何异常都<b>不</b>影响业务连接状态(连接状态机只反映
 *       Kafka 可达性,不应被归档问题拉成 ERROR);但消费线程意外死亡时会把运行
 *       标志复位(写线程随之排空退出,{@code isRunning()} 如实返回 false),
 *       由用户重连或重开 {@code archive_enabled} 触发重启 —— 不做自动重启,
 *       避免坏 topic/坏配置导致的死亡被无限放大成重启循环。</li>
 * </ul>
 */
public class ClusterArchiver implements ClusterConnection.ClusterArchiverHandle {

    private static final Logger log = LoggerFactory.getLogger(ClusterArchiver.class);

    /** 只订阅"非 {@code _} 前缀"的业务 topic(与 log-dirs summary 口径一致)。 */
    private static final Pattern BUSINESS_TOPICS = Pattern.compile("^(?!_).*");

    /** 有界队列:写线程跟不上时触发背压,防止消息在堆里无限堆积。 */
    private static final int QUEUE_CAPACITY = 10_000;
    /** 攒批阈值:够 500 条就写。 */
    private static final int BATCH_SIZE = 500;
    /** 攒批超时:不足 500 条也要在 500ms 内落库,保证低流量集群的可见延迟。 */
    private static final long FLUSH_INTERVAL_MS = 500L;
    /** 台账 + 订阅刷新周期。 */
    private static final long REFRESH_INTERVAL_MS = 10_000L;
    /** 入队等待:队列满时单次等待上限,配合背压循环。 */
    private static final long ENQUEUE_WAIT_MS = 200L;
    /** flush 失败后的重试退避:DB 故障期间写线程按此节奏原地重试同一批。 */
    private static final long FLUSH_RETRY_DELAY_MS = 1_000L;
    /** 专用 ObjectMapper:归档序列化是内部固定形态,不需要 Spring 容器的个性化配置。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final ClusterConnection connection;
    private final ClusterDefinition definition;
    private final ClusterArchiveService archiveService;
    private final ClusterConnectionFactory connectionFactory;
    private final ArchiveProperties archiveProperties;
    /** Admin 调用的阻塞上限,见 kafka.admin.default-api-timeout-ms。 */
    private final long adminTimeoutMs;
    private final BlockingQueue<ClusterArchiveService.ArchivedMessage> queue =
            new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 当前消费者引用,供 {@link #stop()} 从外部 {@code wakeup()} 打断 poll。 */
    private volatile KafkaConsumer<byte[], byte[]> currentConsumer;
    private volatile Thread consumerThread;
    private volatile Thread writerThread;
    /** 最后一次周期刷新的时刻(nanoTime)。 */
    private volatile long lastRefreshNanos;

    public ClusterArchiver(ClusterConnection connection,
                           ClusterDefinition definition,
                           ClusterArchiveService archiveService,
                           ClusterConnectionFactory connectionFactory,
                           ArchiveProperties archiveProperties,
                           KafkaProperties kafkaProperties) {
        this.connection = connection;
        this.definition = definition;
        this.archiveService = archiveService;
        this.connectionFactory = connectionFactory;
        this.archiveProperties = archiveProperties;
        this.adminTimeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
    }

    private static void joinQuietly(Thread thread, String role) {
        if (thread == null) {
            return;
        }
        try {
            // 写线程可能正在做最后一次 flush,给足时间(单批 500 条 + 事务提交)
            thread.join(10_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for archiver {} thread to stop", role);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 启动(幂等:已在运行则直接返回)。 */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        long clusterId = definition.id();
        // 先建表:否则第一批写入才建表,而建表失败会被当成"写失败"反复重试,
        // 日志里看不到真正原因
        try {
            archiveService.ensureArchiveTable(clusterId);
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        consumerThread = new Thread(this::consumerLoop, "archive-consumer-" + clusterId);
        writerThread = new Thread(this::writerLoop, "archive-writer-" + clusterId);
        consumerThread.setDaemon(true);
        writerThread.setDaemon(true);
        consumerThread.start();
        writerThread.start();
        log.info("Archiver started for cluster {} ({}) with group kafkaviz-archive-{}",
                clusterId, definition.name(), clusterId);
    }

    /**
     * 停止:置标志 → {@code wakeup()} 打断 poll → 等两个线程收尾 → 关消费者。
     *
     * <p>{@code wakeup()} 是唯一能从外部安全打断 {@code poll()} 的手段;不用
     * {@code interrupt}:KafkaConsumer 对 interrupt 的处理是抛
     * InterruptException 且**可能**留下不一致的内部状态。
     *
     * <p>用 {@code getAndSet} 而非 compareAndSet:即使运行标志已被消费线程的
     * 意外死亡处理复位(见 {@link #consumerLoop()} 的 catch),wakeup + join
     * 收尾仍要执行 —— 写线程可能还在排空队列做最后 flush,不能就此撒手。
     */
    @Override
    public void stop() {
        boolean wasRunning = running.getAndSet(false);
        if (wasRunning) {
            log.info("Stopping archiver of cluster {}...", definition.id());
        }
        KafkaConsumer<byte[], byte[]> consumer = currentConsumer;
        if (consumer != null) {
            try {
                consumer.wakeup();
            } catch (Exception e) {
                log.debug("wakeup() failed (consumer may already be closed): {}", e.getMessage());
            }
        }
        joinQuietly(consumerThread, "consumer");
        joinQuietly(writerThread, "writer");
        if (wasRunning) {
            log.info("Archiver stopped for cluster {}", definition.id());
        }
    }

    // ---------------------------------------------------------------
    // 消费线程
    // ---------------------------------------------------------------

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void consumerLoop() {
        long clusterId = definition.id();
        try (KafkaConsumer<byte[], byte[]> consumer = connectionFactory.createArchiveConsumer(definition, clusterId)) {
            currentConsumer = consumer;
            consumer.subscribe(BUSINESS_TOPICS);
            refreshTopicsAndRegistry(consumer, true);

            while (running.get()) {
                ConsumerRecords<byte[], byte[]> records;
                try {
                    records = consumer.poll(Duration.ofMillis(1000L));
                } catch (WakeupException e) {
                    // stop() 的约定信号:跳出循环,走 try-with-resources 关闭
                    break;
                } catch (RuntimeException e) {
                    // 单次 poll 失败(分区重分配、元数据抖动)不该终止归档:记 WARN 后继续。
                    // 只有在 stop() 时才会退出循环,因此不会变成死循环空转。
                    log.warn("Archive poll failed for cluster {}: {}", clusterId, e.getMessage());
                    continue;
                }
                if (!running.get()) {
                    break;
                }

                boolean enqueuedAll = true;
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    if (!enqueue(consumer, toArchivedMessage(record))) {
                        enqueuedAll = false;
                        break;
                    }
                }
                if (!enqueuedAll) {
                    break;   // 已停止,不再提交 offset
                }
                // 只提交"已入队"的 offset:写失败时批次保留在写线程里原地重试(不丢),
                // 而提交在前会导致真正的丢数据。主键幂等只服务于重平衡/重启后的
                // offset 回退重放去重,不依赖它补丢失的批次
                try {
                    consumer.commitSync();
                } catch (RuntimeException e) {
                    log.warn("Archive commitSync failed for cluster {}: {}", clusterId, e.getMessage());
                }

                maybeRefresh(consumer);
            }
        } catch (Exception e) {
            if (running.get()) {
                // 消费线程意外死亡:必须置停标志。否则写线程会对空队列每 500ms 空转、
                // isRunning() 恒 true(外部以为归档还在工作,实际已停摆),线程与队列
                // 泄漏到连接关闭为止。置 false 后写线程排空队列自然退出;恢复手段 =
                // 断开重连或 PUT archiveEnabled 触发 restartArchiver 重新启动。
                // 归档问题不影响业务连接状态机,只在此记 ERROR。
                running.set(false);
                log.error("Archive consumer loop terminated unexpectedly for cluster {} — archiver "
                        + "marked stopped (reconnect or re-toggle archive_enabled to restart): {}",
                        definition.id(), e.getMessage(), e);
            }
        } finally {
            currentConsumer = null;
            log.debug("Archive consumer loop exited for cluster {}", definition.id());
        }
    }

    /** @return false = 已停止(调用方应停止投递并退出循环) */
    private boolean enqueue(KafkaConsumer<byte[], byte[]> consumer,
                            ClusterArchiveService.ArchivedMessage message) {
        while (running.get()) {
            try {
                if (queue.offer(message, ENQUEUE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            // 队列满 → 背压:pause 住已分配分区,poll 一轮只维持组心跳(不推进位点),
            // 再 resume 重试投递。这样既不丢消息,也不会让 rebalance 把消费者踢出去。
            backpressurePoll(consumer);
        }
        return false;
    }

    private void backpressurePoll(KafkaConsumer<byte[], byte[]> consumer) {
        Set<TopicPartition> assigned = consumer.assignment();
        if (assigned.isEmpty()) {
            return;
        }
        try {
            consumer.pause(assigned);
            consumer.poll(Duration.ofMillis(200L));
        } catch (WakeupException e) {
            // stop() 正在停止:交给外层循环处理
        } catch (RuntimeException e) {
            log.debug("Backpressure poll failed for cluster {}: {}", definition.id(), e.getMessage());
        } finally {
            if (running.get()) {
                try {
                    consumer.resume(assigned);
                } catch (RuntimeException e) {
                    log.debug("resume() failed for cluster {}: {}", definition.id(), e.getMessage());
                }
            }
        }
    }

    /** 每 {@value #REFRESH_INTERVAL_MS}ms 同步一次 topic 台账(订阅由 Pattern 自动维护)。 */
    private void maybeRefresh(KafkaConsumer<byte[], byte[]> consumer) {
        long now = System.nanoTime();
        if (now - lastRefreshNanos < REFRESH_INTERVAL_MS * 1_000_000L) {
            return;
        }
        refreshTopicsAndRegistry(consumer, false);
    }

    /**
     * 拉一次全量 topic 列表 → 同步 {@code topic_registry} 台账。
     *
     * <p>失败只记 WARN:台账是"曾经存在过哪些 topic"的记录,晚一轮更新不影响归档本体。
     */
    private void refreshTopicsAndRegistry(KafkaConsumer<byte[], byte[]> consumer, boolean initial) {
        lastRefreshNanos = System.nanoTime();
        long clusterId = definition.id();
        try {
            List<String> business = KafkaFutures.await(
                            connection.getAdminClient().listTopics().names(), adminTimeoutMs,
                            "listTopics:archive:cluster=" + clusterId)
                    .stream()
                    .filter(t -> !t.startsWith("_"))
                    .sorted()
                    .toList();

            Map<String, Integer> partitionCounts = fetchPartitionCounts(business);
            if (partitionCounts == null) {
                // 分区数取不到时宁可不更新台账:传 0 会把 last_partition_count 写成错的
                log.warn("Skipping topic registry sync for cluster {} (describeTopics failed)", clusterId);
                return;
            }
            archiveService.syncTopicRegistry(clusterId, business, topic -> partitionCounts.getOrDefault(topic, 0));
            if (initial) {
                log.info("Cluster {} archive subscription initialised with {} business topic(s)",
                        clusterId, business.size());
            }
        } catch (Exception e) {
            log.warn("Topic registry sync failed for cluster {}: {}", clusterId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // 写线程
    // ---------------------------------------------------------------

    /** 一次 describeTopics 取全部分区数;失败返回 null(调用方跳过本轮同步)。 */
    private Map<String, Integer> fetchPartitionCounts(List<String> topics) {
        if (topics.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, TopicDescription> descriptions = KafkaFutures.await(
                    connection.getAdminClient().describeTopics(topics).all(), adminTimeoutMs,
                    "describeTopics:archive:cluster=" + definition.id());
            Map<String, Integer> counts = new LinkedHashMap<>();
            descriptions.forEach((name, desc) -> counts.put(name, desc.partitions().size()));
            return counts;
        } catch (Exception e) {
            return null;
        }
    }

    private void writerLoop() {
        List<ClusterArchiveService.ArchivedMessage> batch = new ArrayList<>(BATCH_SIZE);
        // 上一轮 flush 失败:本批必须先原地重试成功,才能继续从队列取新消息
        boolean retryPending = false;
        while (true) {
            // 失败批次原地重试(见 flush 的 javadoc):消费线程在入队后即提交 offset,
            // 丢批 = 永久丢数据。DB 故障期间写线程在这里退避重试,队列积压后由
            // 消费侧背压暂停 poll,消息不会丢。
            if (retryPending) {
                if (flush(batch)) {
                    batch.clear();
                    retryPending = false;
                } else if (running.get()) {
                    sleepQuietly(FLUSH_RETRY_DELAY_MS);
                    continue;
                }
                // 停止中失败:不清批、不退出,继续排空队列,由收尾分支做最后尝试
            }
            ClusterArchiveService.ArchivedMessage head;
            try {
                head = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                flushQuietly(batch);   // 收尾:中断也要尽力把余量写完
                return;
            }
            if (head != null) {
                batch.add(head);
                queue.drainTo(batch, BATCH_SIZE - 1);
            }
            boolean idle = head == null;
            if (idle && !running.get() && queue.isEmpty()) {
                flushQuietly(batch);   // 收尾:停止前把余量写完(含此前失败未落库的)
                return;
            }
            if (!batch.isEmpty() && (batch.size() >= BATCH_SIZE || idle)) {
                if (flush(batch)) {
                    batch.clear();
                } else {
                    // 失败不清批:批次留在内存里,下一轮循环顶部原地重试
                    retryPending = true;
                }
            }
        }
    }

    /**
     * 落一批。
     *
     * <p>失败<b>不清批</b>:消费线程在入队后即 {@code commitSync},一旦这里丢批,
     * 该段消息就永久丢失(offset 已提交,"靠重放补齐"的前提不成立)。失败批次保留
     * 在内存里由 {@link #writerLoop()} 原地退避重试,DB 故障期间队列积压、消费侧
     * 背压暂停 poll,消息不丢;只有停止时仍失败的余量才会在 {@link #flushQuietly}
     * 里记 ERROR 放弃。
     *
     * <p>本方法<b>从不修改</b> batch:成功与否都由调用方决定清批时机
     * (成功 → 调用方 clear;失败 → 调用方保留重试)。
     *
     * @return true = 已落库,调用方应清空批次;false = 失败,批次已保留待重试
     */
    private boolean flush(List<ClusterArchiveService.ArchivedMessage> batch) {
        if (batch.isEmpty()) {
            return true;
        }
        try {
            archiveService.appendMessages(definition.id(), batch);
            log.debug("Archived {} message(s) for cluster {}", batch.size(), definition.id());
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to archive {} message(s) for cluster {}, batch kept for in-place retry: {}",
                    batch.size(), definition.id(), e.getMessage());
            return false;
        }
    }

    /**
     * 收尾专用 flush(停止/中断路径的最后尝试):成功则清批;仍失败只能记 ERROR
     * 放弃 —— 归档器已在停止,没有第三条路可走。
     */
    private void flushQuietly(List<ClusterArchiveService.ArchivedMessage> batch) {
        if (batch.isEmpty()) {
            return;
        }
        if (flush(batch)) {
            batch.clear();
            return;
        }
        log.error("Dropped {} unarchived message(s) for cluster {} on shutdown: storage write kept "
                + "failing and offsets were already committed", batch.size(), definition.id());
    }

    // ---------------------------------------------------------------
    // 消息转换
    // ---------------------------------------------------------------

    /**
     * 单条消息 → 归档行,含"体量保护":
     * <ul>
     *   <li>value 超过 {@code archive.max-message-bytes} → 存 NULL,并在 headers 里加
     *       {@code __truncated:true},保证 offset 连续性不断档(否则用户会看到"消息丢了");
     *   <li>headers 序列化后超过 {@code archive.max-header-bytes} → 整体替换为截断标记;
     *   <li>value 为 NULL(tombstone,compact topic 的删除标记)→ valueBytes 为 null,
     *       照常按 offset 记录一条。</li>
     * </ul>
     */
    private ClusterArchiveService.ArchivedMessage toArchivedMessage(ConsumerRecord<byte[], byte[]> record) {
        byte[] key = record.key();
        byte[] value = record.value();
        boolean truncatedValue = false;
        if (value != null && value.length > archiveProperties.getMaxMessageBytes()) {
            value = null;
            truncatedValue = true;
        }

        String headersJson = serializeHeaders(record, truncatedValue);

        return new ClusterArchiveService.ArchivedMessage(
                record.topic(),
                record.partition(),
                record.offset(),
                record.timestamp(),
                record.timestampType() == null ? null : record.timestampType().name(),
                key,
                value,
                headersJson);
    }

    /**
     * headers → JSON 文本。
     *
     * <p>用 ObjectMapper 而不是手拼:header 值可能含引号/换行/非 UTF-8 字节,
     * 手拼必然产生读不回来的 JSON。非 UTF-8 的 header 值用 ISO-8859-1 无损转换
     * (二进制 header 属边缘场景,保真优先于可读)。
     */
    private String serializeHeaders(ConsumerRecord<byte[], byte[]> record, boolean truncatedValue) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (truncatedValue) {
            headers.put("__truncated", "true");
        }
        if (record.headers() != null) {
            for (Header header : record.headers()) {
                byte[] raw = header.value();
                headers.put(header.key(), raw == null ? null : new String(raw, StandardCharsets.ISO_8859_1));
            }
        }
        if (headers.isEmpty()) {
            return null;
        }
        try {
            String json = JSON.writeValueAsString(headers);
            if (json.getBytes(StandardCharsets.UTF_8).length > archiveProperties.getMaxHeaderBytes()) {
                return "{\"__truncated\":true}";
            }
            return json;
        } catch (Exception e) {
            log.debug("Failed to serialize headers of {}-{}: {}", record.topic(), record.offset(), e.getMessage());
            return "{\"__truncated\":true}";
        }
    }
}
