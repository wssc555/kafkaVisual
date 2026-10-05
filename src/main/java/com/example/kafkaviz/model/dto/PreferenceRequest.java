package com.example.kafkaviz.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * {@code PUT /api/preferences} 的请求体:单键写入,幂等。
 *
 * <p>不做批量:前端的偏好写入是 debounce 后的单键更新,批量接口只会多一种
 * 需要测试的事务边界。
 */
@Data
public class PreferenceRequest {

    @NotBlank(message = "key must not be blank")
    private String key;

    /** 允许空串(表示"清空该偏好"),因此不加 @NotBlank。 */
    private String value;
}
