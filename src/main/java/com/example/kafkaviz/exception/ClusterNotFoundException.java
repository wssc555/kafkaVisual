package com.example.kafkaviz.exception;

/**
 * 集群配置不存在。
 *
 * <p>错误码 404 / {@code 40404}
 * (避开 ZK 节点已占用的 {@code 40403})。
 *
 * <p>注意与 {@link ServiceUnavailableException}(50302)的边界:
 * 「配置不存在」是 404 —— 用户配错了 id,重试无用;
 * 「配置存在但连不上/正在连」是 50302 —— 可重试。
 */
public class ClusterNotFoundException extends RuntimeException {

    private final long clusterId;

    public ClusterNotFoundException(long clusterId) {
        super("Cluster not found: " + clusterId);
        this.clusterId = clusterId;
    }

    public long getClusterId() {
        return clusterId;
    }
}
