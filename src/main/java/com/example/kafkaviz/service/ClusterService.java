package com.example.kafkaviz.service;

import com.example.kafkaviz.config.CacheConfig;
import com.example.kafkaviz.config.KafkaProperties;
import com.example.kafkaviz.kafka.ClusterConnection;
import com.example.kafkaviz.kafka.KafkaFutures;
import com.example.kafkaviz.model.vo.ClusterInfo;
import com.example.kafkaviz.model.vo.ClusterMode;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 集群信息 / 模式查询。
 *
 * <p>所有方法首参 {@link ClusterConnection} —— AdminClient 来自该连接。
 * 模式判断依据是 {@link #getClusterMode(ClusterConnection)} 里的"这个连接有没有 ZK":
 * 多集群下 ZK 是<b>按集群</b>的属性,不存在全局开关。
 *
 * <p>缓存 key 用 {@code CacheKey(clusterId, arg)} 复合键:
 * 裸 String key 在多集群下不成立,TTL 内 A 集群的数据会被 B 集群读到。
 */
@Service
public class ClusterService {

    /** Admin 调用的阻塞上限,见 kafka.admin.default-api-timeout-ms。 */
    private final long timeoutMs;

    public ClusterService(KafkaProperties kafkaProperties) {
        this.timeoutMs = kafkaProperties.getAdmin().getDefaultApiTimeoutMs();
    }

    /**
     * 查询集群信息(broker 列表 / clusterId / controller)。
     *
     * <p>属于秒级~分钟级才变化的数据,加 10s 短 TTL 缓存:前端集群信息页与元数据页
     * 每次进入都会拉一次,叠加 Dashboard 的聚合调用,不缓存时打点频率很高。
     *
     * <p>broker 增删不通过本应用,故无需失效逻辑;方法抛出的异常不会被缓存,
     * 集群不可达时后续请求仍会真实调用。
     */
    @Cacheable(value = CacheConfig.CLUSTER_INFO,
            key = "T(com.example.kafkaviz.kafka.CacheKey).of(#connection.clusterId(), 'cluster')")
    public ClusterInfo describeCluster(ClusterConnection connection) throws ExecutionException, InterruptedException {
        AdminClient admin = connection.getAdminClient();
        DescribeClusterResult result = admin.describeCluster();

        String clusterId = KafkaFutures.await(result.clusterId(), timeoutMs, "describeCluster.clusterId");
        Node controller = KafkaFutures.await(result.controller(), timeoutMs, "describeCluster.controller");
        List<Node> nodes = new java.util.ArrayList<>(
                KafkaFutures.await(result.nodes(), timeoutMs, "describeCluster.nodes"));

        List<ClusterInfo.BrokerInfo> brokers = nodes.stream()
                .map(n -> ClusterInfo.BrokerInfo.builder()
                        .id(n.id())
                        .host(n.host())
                        .port(n.port())
                        .rack(n.rack())
                        .build())
                .toList();

        return ClusterInfo.builder()
                .clusterId(clusterId)
                .controllerId(controller.id())
                .brokers(brokers)
                .build();
    }

    /**
     * 返回该集群的运行模式(KRAFT / ZOOKEEPER)。
     *
     * <p>判断依据是该连接是否持有 Curator —— 由集群配置的 {@code zk_connect_string}
     * 是否非空决定(见 {@code ClusterConnectionFactory.create})。
     * 因此"这个集群有没有 ZK"是运行时的事实,而不是全局配置的推断。
     */
    public ClusterMode getClusterMode(ClusterConnection connection) {
        boolean zkAvailable = connection.zkAvailable();
        ClusterMode.Mode mode = zkAvailable ? ClusterMode.Mode.ZOOKEEPER : ClusterMode.Mode.KRAFT;
        return ClusterMode.builder()
                .mode(mode)
                .zkAvailable(zkAvailable)
                .build();
    }
}
