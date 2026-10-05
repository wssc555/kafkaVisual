package com.example.kafkaviz.config;

import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.model.dto.ClusterUpsertRequest;
import com.example.kafkaviz.storage.ClusterConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 种子导入:把 {@code application.yml} 里的 kafka/zk 配置
 * 降级为「首次启动种子」,保证存量部署平滑过渡到多集群模型。
 *
 * <p>规则:集群表为空 <b>且</b> {@code kafka.bootstrap-servers} 非空 →
 * 导入一条 name={@value #SEED_NAME} 的记录(含 security / zk.connect-string)。
 * 此后 SQLite 是唯一事实源,再改 yml 不会影响已有配置。
 *
 * <p><b>为什么独立于 StartupValidator</b>:{@code BaseApiIT} 用
 * {@code @MockitoBean} 屏蔽了 StartupValidator,而测试基建恰恰依赖种子导入
 * 自动产生那一条 default 集群(测试没法手工插库)。两者合在一起会被一起 mock 掉。
 */
@Component
@Order(ClusterSeedImporter.ORDER)
public class ClusterSeedImporter implements ApplicationRunner {

    /** 晚于建表(0)、早于启动自检。 */
    public static final int ORDER = 10;

    static final String SEED_NAME = "default";

    private static final Logger log = LoggerFactory.getLogger(ClusterSeedImporter.class);

    private final ClusterConfigStore store;
    private final KafkaProperties kafkaProperties;
    private final ZkProperties zkProperties;

    public ClusterSeedImporter(ClusterConfigStore store,
                               KafkaProperties kafkaProperties,
                               ZkProperties zkProperties) {
        this.store = store;
        this.kafkaProperties = kafkaProperties;
        this.zkProperties = zkProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String servers = kafkaProperties.getBootstrapServers();
        if (servers == null || servers.isBlank()) {
            log.debug("No kafka.bootstrap-servers configured — skipping cluster seed import");
            return;
        }
        int existing = store.count();
        if (existing > 0) {
            log.debug("Cluster table already has {} row(s) — skipping seed import (identical result on re-run)",
                    existing);
            return;
        }

        ClusterUpsertRequest req = new ClusterUpsertRequest();
        req.setName(SEED_NAME);
        req.setBootstrapServers(servers);
        req.setSecurityProtocol(kafkaProperties.getSecurity().getProtocol());
        req.setSaslMechanism(kafkaProperties.getSecurity().getSaslMechanism());
        req.setUsername(kafkaProperties.getSecurity().getUsername());
        req.setPassword(kafkaProperties.getSecurity().getPassword());
        req.setZkConnectString(zkProperties.getConnectString());
        req.setArchiveEnabled(true);

        ClusterDefinition imported = store.insert(req);
        log.info("Imported seed cluster '{}' (id={}, servers={}, zk={}) from application.yml — "
                + "this happens only once; the database is the single source of truth from now on",
                imported.name(), imported.id(), imported.bootstrapServers(), imported.zkConfigured());
    }
}
