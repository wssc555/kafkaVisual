package com.example.kafkaviz.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 收藏项。{@code (clusterId, itemType, itemName)} 三元组唯一。
 */
@Data
@Builder
public class FavoriteItem {

    private long clusterId;

    /** {@code topic} 或 {@code group}。 */
    private String itemType;

    private String itemName;
}
