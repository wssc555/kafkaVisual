package com.example.kafkaviz.kafka;

/**
 * 集群连接状态机。
 *
 * <p>内部四态 → 对外展示三态:{@link #displayState()}
 * ERROR 与 DISCONNECTED 都归并为 {@code OFFLINE}(用户只关心"能不能看实时数据"),
 * 差异通过 {@code errorSummary} 透出。
 */
public enum ClusterState {

    /** 从未连过、已被显式断开、或被空闲回收。 */
    DISCONNECTED,
    /** 正在建连/探活。 */
    CONNECTING,
    /** 探活成功,可服务实时端点。 */
    CONNECTED,
    /** 建连或探活失败,保留最近一次失败摘要。 */
    ERROR;

    /** 对外展示态:ONLINE / OFFLINE / CONNECTING。 */
    public String displayState() {
        return switch (this) {
            case CONNECTED -> "ONLINE";
            case CONNECTING -> "CONNECTING";
            case DISCONNECTED, ERROR -> "OFFLINE";
        };
    }
}
