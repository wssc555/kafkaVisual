package com.example.kafkaviz.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * {@code GET /api/storage} 的回显 VO。
 *
 * <p><b>口令永不回明文</b>:有口令时固定输出 {@link #PASSWORD_MASK}。
 */
@Data
@Builder
public class StorageInfo {

    /** GET 回显时替代真实口令的哨兵值;提交该值表示"不修改口令"。 */
    public static final String PASSWORD_MASK = "******";

    /** {@code storage.json} 中记录的类型。 */
    private String type;

    private String host;

    private Integer port;

    private String database;

    private String username;

    /** 有口令 → {@code "******"};无 → {@code ""}。 */
    private String password;

    private String extraParams;

    /**
     * 当前 Spring 上下文里<b>实际生效</b>的类型。
     *
     * <p>与 {@link #type} 不一致 = 用户已保存但尚未重启(切换必须重启生效)。
     */
    private String activeType;

    /** {@code type != activeType} 的便捷标记,供前端直接横幅提示。 */
    private boolean restartRequired;

    /** {@code storage.json} 的绝对路径,便于用户手动排查。 */
    private String configFile;
}
