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
public class ZkChildrenResult {
    private String path;
    private ZkNode.ZkNodeStat stat;
    private List<ZkNode> children;
    /**
     * 递归列出是否被上限截断(true=结果不完整);null 表示未截断,JSON 中被 NON_NULL 省略。
     */
    private Boolean truncated;
}