package com.example.kafkaviz.kafka;

/**
 * 归档器的启动钩子(由 {@code archive} 包实现)。
 *
 * <p>为什么抽出接口:{@code ClusterConnectionManager} 需要"建连成功后启动归档器、
 * 断开时停止",但归档是<b>可选能力</b>。用接口 + {@code ObjectProvider} 注入,
 * 使连接管理器不直接依赖 {@code archive} 包的具体实现,也让 manager 在归档模块
 * 缺席时仍可独立工作。
 */
public interface ArchiverStarter {

    /**
     * 启动该连接的归档器(实现方负责判断 {@code definition.archiveEnabled()})。
     *
     * @return 归档器句柄,交给 {@link ClusterConnection#startArchiver} 持有;不该启动时返回 null
     */
    ClusterConnection.ClusterArchiverHandle start(ClusterConnection connection, ClusterDefinition definition);
}
