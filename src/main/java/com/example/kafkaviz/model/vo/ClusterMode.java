package com.example.kafkaviz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 集群运行模式标识。
 *
 * <p>按<b>集群</b>判断模式:前端经 {@code GET /api/c/{clusterId}/cluster/mode}
 * 获取,根据 {@code mode} 决定当前激活集群的 UI:
 * <ul>
 *   <li>{@link Mode#ZOOKEEPER} — 显示 ZK 节点浏览器入口({@code /api/c/{id}/zk/**} 可用)。</li>
 *   <li>{@link Mode#KRAFT} — 隐藏 ZK 入口,改显示 KRaft 元数据浏览器
 *       ({@code /api/c/{id}/cluster/metadata/**})。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterMode {

    /** 当前模式,由该集群的 zk_connect_string 是否非空决定。 */
    private Mode mode;
    /** ZK 模式下为 true(KRaft 模式下该集群没有 Curator)。 */
    private boolean zkAvailable;

    public enum Mode {
        /** ZooKeeper 模式:该集群配置了 ZK,暴露 {@code /api/c/{id}/zk/**}。对应 Kafka &lt; 4.0。 */
        ZOOKEEPER,
        /** KRaft 模式:该集群无 ZK,暴露 {@code /api/c/{id}/cluster/metadata/**}。对应 Kafka 3.3+ / 4.0。 */
        KRAFT
    }
}
