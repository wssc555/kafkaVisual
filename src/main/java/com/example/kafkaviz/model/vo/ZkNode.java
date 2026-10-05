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
public class ZkNode {
    private String path;
    private ZkNodeStat stat;
    private List<ZkNode> children;
    /**
     * 递归列出是否因深度/节点数上限被截断;null 表示未截断(JSON 中被 NON_NULL 省略)。
     */
    private Boolean truncated;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ZkNodeStat {
        private long czxid;
        private long mzxid;
        private long ctime;
        private long mtime;
        private int version;
        private int cversion;
        private int aversion;
        private long ephemeralOwner;
        private int dataLength;
        private int numChildren;
        private long pzxid;
    }
}