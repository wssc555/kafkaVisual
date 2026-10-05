package com.example.kafkaviz.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * {@code POST /api/c/{clusterId}/favorites} 的请求体。
 *
 * <p>用 body 而不是 query 参数:收藏是"创建一个资源",语义上更接近 POST body;
 * 删除则用 query 参数(DELETE 带 body 在部分代理/客户端上会被丢弃)。
 */
@Data
public class FavoriteRequest {

    /** {@code topic} 或 {@code group};越界由 Store 抛 IllegalArgumentException → 40001。 */
    @NotBlank(message = "type must not be blank")
    private String type;

    @NotBlank(message = "name must not be blank")
    private String name;
}
