package com.example.kafkaviz.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterInfo {
    private String clusterId;
    private int controllerId;
    private List<BrokerInfo> brokers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrokerInfo {
        private int id;
        private String host;
        private int port;
        private String rack;
    }
}