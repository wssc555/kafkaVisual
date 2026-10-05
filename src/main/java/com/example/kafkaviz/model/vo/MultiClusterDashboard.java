package com.example.kafkaviz.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * {@code GET /api/dashboard/multi-overview} 的单集群卡片。
 *
 * <p>跨集群聚合端点<b>不带集群段</b>(聚合本身就是全局视图),一次返回全部集群的卡片。
 *
 * <p>三种卡片形态由字段组合表达:
 * <ul>
 *   <li><b>在线</b>:{@code overview} 有值,{@code archivedMessages} 为 null
 *       (在线集群不做每 10s 一次的 COUNT(*),归档量对大表不便宜);</li>
 *   <li><b>离线 / 连接中</b>:{@code overview} 为 null;{@code archive_enabled} 的集群
 *       带 {@code archivedMessages}(本地归档条数,<b>-1 = 归档量查询失败</b>,
 *       不让一次 COUNT 失败毁掉整张卡片);</li>
 *   <li><b>在线但指标查询失败</b>:per-cluster try/catch,
 *       {@code overview} 为 null、{@code errorSummary} 带摘要 —— 单集群失败不拖垮其它卡片。</li>
 * </ul>
 */
@Data
@Builder
public class MultiClusterDashboard {

    private long clusterId;

    private String name;

    /** ONLINE / OFFLINE / CONNECTING(与 {@link ClusterSummary#getDisplayState()} 同口径)。 */
    private String displayState;

    /** 在线集群的指标聚合(口径与单集群 {@code /c/{id}/dashboard/overview} 一致);其余形态为 null。 */
    private DashboardOverview overview;

    /** 单集群指标查询失败摘要,或该集群 ERROR 态的最近一次失败摘要;正常为 null。 */
    private String errorSummary;

    /** 离线集群的本地归档条数;在线集群为 null;-1 = 归档量查询失败(容错占位)。 */
    private Long archivedMessages;
}
