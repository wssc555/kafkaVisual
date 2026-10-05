package com.example.kafkaviz.controller;

import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.model.dto.FavoriteRequest;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.FavoriteItem;
import com.example.kafkaviz.storage.FavoriteStore;
import com.example.kafkaviz.web.ClusterId;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收藏(topic / 消费组星标)。
 *
 * <p>挂集群段({@code /api/c/{clusterId}/favorites})而不是全局
 * {@code /api/favorites}:收藏天然属于某个集群(同一个 topic 名在两个集群下是两条记录),
 * 放集群段让"当前集群的收藏"成为一次路径就能表达的语义。
 *
 * <p>与归档一样是<b>离线可用</b>的:集群参数只做存在性校验,不触发建连。
 */
@RestController
@RequestMapping("/api/c/{clusterId}/favorites")
public class FavoriteController {

    private final FavoriteStore favoriteStore;

    public FavoriteController(FavoriteStore favoriteStore) {
        this.favoriteStore = favoriteStore;
    }

    /** 当前集群的全部收藏。 */
    @GetMapping
    public ApiResponse<List<FavoriteItem>> listFavorites(@PathVariable long clusterId,
                                                        @ClusterId(existenceOnly = true) ClusterDefinition cluster) {
        return ApiResponse.ok(favoriteStore.list(clusterId));
    }

    /** 添加收藏(幂等:重复添加不报错)。 */
    @PostMapping
    public ApiResponse<List<FavoriteItem>> addFavorite(@PathVariable long clusterId,
                                                      @ClusterId(existenceOnly = true) ClusterDefinition cluster,
                                                      @Valid @RequestBody FavoriteRequest req) {
        favoriteStore.add(clusterId, req.getType(), req.getName());
        return ApiResponse.ok(favoriteStore.list(clusterId));
    }

    /**
     * 移除收藏(幂等:不存在时静默成功)。
     *
     * <p>用 query 参数而非 DELETE body:部分代理/客户端会丢弃 DELETE 的 body。
     */
    @DeleteMapping
    public ApiResponse<List<FavoriteItem>> removeFavorite(@PathVariable long clusterId,
                                                         @ClusterId(existenceOnly = true) ClusterDefinition cluster,
                                                         @RequestParam String type,
                                                         @RequestParam String name) {
        favoriteStore.remove(clusterId, type, name);
        return ApiResponse.ok(favoriteStore.list(clusterId));
    }
}
