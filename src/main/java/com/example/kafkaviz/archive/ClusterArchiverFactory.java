package com.example.kafkaviz.archive;

import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.kafka.ArchiverStarter;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.ClusterConnectionFactory;
import com.example.kafkaviz.kafka.ClusterDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 归档器启动入口:实现 {@link ArchiverStarter},让
 * {@code ClusterConnectionManager} 在<b>建连成功后</b>启动归档、断开时停止,
 * 而连接管理器本身不依赖 {@code archive} 包(见 {@link ArchiverStarter} 的说明)。
 *
 * <p>两个前置条件都可能让本工厂返回 {@code null}(=不启动归档):
 * <ol>
 *   <li>全局开关 {@code archive.enabled=false}(测试基建与受限环境用);</li>
 *   <li>该集群的 {@code archive_enabled=0}。</li>
 * </ol>
 */
@Component
public class ClusterArchiverFactory implements ArchiverStarter {

    private static final Logger log = LoggerFactory.getLogger(ClusterArchiverFactory.class);

    private final ClusterArchiveService archiveService;
    private final ClusterConnectionFactory connectionFactory;
    private final ArchiveProperties archiveProperties;
    private final KafkaProperties kafkaProperties;

    public ClusterArchiverFactory(ClusterArchiveService archiveService,
                                 ClusterConnectionFactory connectionFactory,
                                 ArchiveProperties archiveProperties,
                                 KafkaProperties kafkaProperties) {
        this.archiveService = archiveService;
        this.connectionFactory = connectionFactory;
        this.archiveProperties = archiveProperties;
        this.kafkaProperties = kafkaProperties;
    }

    @Override
    public ClusterConnection.ClusterArchiverHandle start(ClusterConnection connection, ClusterDefinition definition) {
        if (!archiveProperties.isEnabled()) {
            log.debug("Archive globally disabled (archive.enabled=false) — not starting archiver for cluster {}",
                    definition.id());
            return null;
        }
        if (!definition.archiveEnabled()) {
            log.debug("Archiving disabled for cluster {} (archive_enabled=0) — not starting archiver",
                    definition.id());
            return null;
        }
        ClusterArchiver archiver = new ClusterArchiver(connection, definition, archiveService,
                connectionFactory, archiveProperties, kafkaProperties);
        archiver.start();
        return archiver;
    }
}
