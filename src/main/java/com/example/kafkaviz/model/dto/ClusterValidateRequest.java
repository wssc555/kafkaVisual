package com.example.kafkaviz.model.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code POST /api/clusters/validate} 的请求体。
 *
 * <p>与 upsert 请求体<b>同构</b>(继承全部字段),只多一个
 * {@link #clusterId} —— 这样"测连通过的配置"与"保存的配置"是同一份数据,
 * 不会出现两套字段语义。
 *
 * <p><b>三态的处理与 upsert 一致</b>(这点比"测连必须重输凭据"友好得多):
 * 编辑已有集群时前端不必重输密码/私钥 —— 提交 {@code clusterId} 且把未修改的
 * 秘密字段留空,服务端会从库中取出原值补全后再测连。新建场景 {@code clusterId}
 * 为空,凭据必须给齐。
 *
 * <p>{@code name} 字段沿用父类的 {@code @NotBlank} 校验,但本端点<b>不做</b>
 * {@code @Valid} 校验(测连不需要名字),因此留空即可。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ClusterValidateRequest extends ClusterUpsertRequest {

    /** 编辑已有集群时提交其 id;为空 = 新建前的试连(凭据必须全量)。 */
    private Long clusterId;
}
