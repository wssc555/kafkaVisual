package com.example.kafkaviz.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class UpdateTopicConfigsRequest {

    /** 键值对;value=null 表示删除该配置覆盖项(恢复 broker 默认)。 */
    @NotNull
    @NotEmpty(message = "configs must not be empty")
    private Map<String, String> configs;
}