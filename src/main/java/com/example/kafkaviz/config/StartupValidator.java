package com.example.kafkaviz.config;

import com.example.kafkaviz.storage.ClusterConfigStore;
import com.example.kafkaviz.storage.StorageDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动自检:启动时对集群配置做<b>只读汇报</b>(存储后端、集群配置数),
 * 失败不中断进程。
 *
 * <p>连通性不在这里检查:一个集群坏掉不该杀死整个进程,交给
 * {@code ClusterConnectionManager} 的按集群状态机。
 *
 * <p>{@code BaseApiIT} 用 {@code @MockitoBean StartupValidator} 屏蔽它 ——
 * 本身无害,mock 仍保留只是为了少一次启动期 DB 查询。
 */
@Component
@Order(StartupValidator.ORDER)
public class StartupValidator implements ApplicationRunner {

    /** 在种子导入之后(那才是最终集群数)。 */
    public static final int ORDER = ClusterSeedImporter.ORDER + 10;

    private static final Logger log = LoggerFactory.getLogger(StartupValidator.class);

    private final ClusterConfigStore clusterConfigStore;
    private final StorageDialect storageDialect;

    public StartupValidator(ClusterConfigStore clusterConfigStore, StorageDialect storageDialect) {
        this.clusterConfigStore = clusterConfigStore;
        this.storageDialect = storageDialect;
    }

    @Override
    public void run(ApplicationArguments args) {
        int clusterCount;
        try {
            clusterCount = clusterConfigStore.count();
        } catch (RuntimeException e) {
            // 这里不能让启动失败:配置表读不到时前端仍应能起来并展示错误,
            // 否则用户连"去设置页改数据源"的入口都没有。
            log.error("Failed to read cluster configuration count: {}", e.getMessage());
            log.info("=== Startup summary: storage={}, cluster configuration unreadable ===",
                    storageDialect.type().configValue());
            return;
        }
        if (clusterCount == 0) {
            log.info("=== Startup summary: storage={}, no cluster configured — add one via the UI "
                    + "(or set kafka.bootstrap-servers to seed one), then connect it ===",
                    storageDialect.type().configValue());
        } else {
            log.info("=== Startup summary: storage={}, {} cluster(s) configured "
                    + "(connections are established lazily on first request) ===",
                    storageDialect.type().configValue(), clusterCount);
        }
    }
}
