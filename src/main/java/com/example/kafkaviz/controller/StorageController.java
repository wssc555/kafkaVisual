package com.example.kafkaviz.controller;

import com.example.kafkaviz.model.dto.StorageConfigRequest;
import com.example.kafkaviz.model.dto.StorageTestResult;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.model.vo.StorageInfo;
import com.example.kafkaviz.service.StorageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据源配置端点。
 *
 * <p>不带集群段:数据源是<b>应用级</b>配置,与具体 Kafka 集群无关。
 *
 * <p>错误码纪律:参数问题 {@link IllegalArgumentException} → 40001(GlobalExceptionHandler
 * 已有映射);不新增错误码。
 */
@RestController
@RequestMapping("/api/storage")
public class StorageController {

    private final StorageService storageService;

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    /** 读取当前数据源配置(口令打码,永不回明文)。 */
    @GetMapping
    public ApiResponse<StorageInfo> getStorageConfig() {
        return ApiResponse.ok(storageService.getInfo());
    }

    /**
     * 保存数据源配置。<b>不热切换</b> —— 重启后生效(msg 已明确提示)。
     *
     * <p><b>保存前服务端强制 test 通过</b>:对请求体的同一份配置先做
     * 一次真实建连,失败抛 {@link IllegalArgumentException} → 40001,不落盘。
     * 前端 UI 的"测试通过才允许保存"门控仍保留,作为体验层的双保险。
     */
    @PutMapping
    public ApiResponse<StorageInfo> saveStorageConfig(@RequestBody StorageConfigRequest req) {
        String msg = storageService.save(req);
        StorageInfo info = storageService.getInfo();
        // 用构造器而非 ApiResponse.ok():msg 要携带"需重启生效"的提示,不能是 "ok"
        return new ApiResponse<>(0, msg, info);
    }

    /**
     * 测试候选配置的连接(不落盘)。
     *
     * <p>失败返回 200 + {@code success=false}(测试失败是正常业务结果,不是服务端错误)。
     */
    @PostMapping("/test")
    public ApiResponse<StorageTestResult> testStorageConfig(@RequestBody StorageConfigRequest req) {
        return ApiResponse.ok(storageService.test(req));
    }
}
