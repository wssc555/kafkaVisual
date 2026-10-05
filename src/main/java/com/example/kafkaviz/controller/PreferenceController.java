package com.example.kafkaviz.controller;

import com.example.kafkaviz.model.dto.PreferenceRequest;
import com.example.kafkaviz.model.vo.ApiResponse;
import com.example.kafkaviz.storage.PreferenceStore;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 界面偏好 KV。
 *
 * <p>不带集群段:偏好是<b>应用级</b>的(侧栏折叠状态、激活集群 id),与集群无关。
 */
@RestController
@RequestMapping("/api/preferences")
public class PreferenceController {

    private final PreferenceStore preferenceStore;

    public PreferenceController(PreferenceStore preferenceStore) {
        this.preferenceStore = preferenceStore;
    }

    /** 全量读取(前端启动时一次拉回)。 */
    @GetMapping
    public ApiResponse<Map<String, String>> getPreferences() {
        return ApiResponse.ok(preferenceStore.getAll());
    }

    /** 单键写入(幂等)。value 允许空串=清空。 */
    @PutMapping
    public ApiResponse<Map<String, String>> putPreference(@Valid @RequestBody PreferenceRequest req) {
        preferenceStore.put(req.getKey(), req.getValue());
        return ApiResponse.ok(Map.of(req.getKey(), req.getValue() == null ? "" : req.getValue()));
    }
}
