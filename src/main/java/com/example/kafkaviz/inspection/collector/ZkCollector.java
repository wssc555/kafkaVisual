package com.example.kafkaviz.inspection.collector;

import com.example.kafkaviz.inspection.metric.MetricScope;
import com.example.kafkaviz.kafka.ClusterConnection;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * ZK 节点级指标采集。
 *
 * <p><b>逐集群判定</b>:{@code connection.zkAvailable()} 为 false(KRaft 集群,
 * 或该集群未配 {@code zk_connect_string})时直接返回空 Map —— 不抛错、不重试。
 * 调用方拿到空 Map 即"本 scope 无数据",由引擎决定跳过。
 *
 * <p>{@code node_value_hash}:值 bytes 的 SHA-256 前 8 字节按大端转 long,再转十进制串。
 * 相同存储成本下比"值长度"精确得多 —— 长度相同但内容变了的情况能被检出,
 * 这是"节点被改动"告警的实际需求。
 */
@Component
public class ZkCollector implements MetricCollector {

    private static final Logger log = LoggerFactory.getLogger(ZkCollector.class);

    @Override
    public MetricScope scope() {
        return MetricScope.ZK;
    }

    @Override
    public Map<String, Map<String, String>> collect(ClusterConnection connection,
                                                    Collection<String> scopeInstances,
                                                    Set<String> metricKeys) {
        if (!connection.zkAvailable()) {
            return Map.of();
        }
        CuratorFramework curator = connection.getCurator();
        if (curator == null) {
            return Map.of();
        }

        Map<String, Map<String, String>> out = new HashMap<>();
        for (String path : scopeInstances) {
            if (path == null || path.isBlank()) {
                continue;
            }
            Map<String, String> values = new HashMap<>();
            try {
                Stat stat = curator.checkExists().forPath(path);
                boolean exists = stat != null;
                if (metricKeys.contains("node_exists")) {
                    values.put("node_exists", String.valueOf(exists));
                }
                if (metricKeys.contains("child_count")) {
                    values.put("child_count", String.valueOf(exists ? stat.getNumChildren() : 0));
                }
                if (metricKeys.contains("node_value_hash")) {
                    values.put("node_value_hash", String.valueOf(hashOf(curator, path, exists)));
                }
            } catch (Exception e) {
                // 单节点读取失败(权限/连接抖动)不该让整条规则失败:该 scope 本轮无值,
                // 引擎按"缺值 → 样本不足"处理,比把整轮评估炸掉更符合巡检的容错语义。
                log.warn("ZK metric collection failed for path {}: {}", path, e.getMessage());
                continue;
            }
            out.put(path, values);
        }
        return out;
    }

    /** 计算节点值 hash;节点不存在或读取失败时返回 0(与"空值 hash"区分开由 node_exists 承担)。 */
    private long hashOf(CuratorFramework curator, String path, boolean exists) {
        if (!exists) {
            return 0L;
        }
        try {
            byte[] data = curator.getData().forPath(path);
            if (data == null || data.length == 0) {
                return 0L;
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFFL);
            }
            return value;
        } catch (Exception e) {
            log.warn("Failed to read ZK value for hash, path {}: {}", path, e.getMessage());
            return 0L;
        }
    }
}
