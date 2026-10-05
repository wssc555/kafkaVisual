package com.example.kafkaviz.storage;

import lombok.Data;

/**
 * 数据源配置(storage.json 的 Java 形态)。
 *
 * <p>type=SQLITE 时 {@code host/port/database/username/passwordCipher/extraParams} 全部忽略
 * (库文件位置由 {@code --app.db-path} 决定)。
 */
@Data
public class StorageConfig {

    /** 当前启用的数据源类型。 */
    private StorageType type = StorageType.SQLITE;

    /** PG/MySQL 主机。 */
    private String host = "";

    /** PG/MySQL 端口;0 表示用方言默认值(5432 / 3306)。 */
    private int port = 0;

    /** PG/MySQL 库名。 */
    private String database = "";

    /** PG/MySQL 账号。 */
    private String username = "";

    /**
     * 口令的 AES-GCM 密文(Base64)。空串 = 无口令。
     *
     * <p>明文口令<b>永不</b>落盘;加解密口径见 {@link com.example.kafkaviz.security.CryptoService}。
     */
    private String passwordCipher = "";

    /**
     * 追加到 JDBC URL 的高级参数(形如 {@code ssl=true&connectTimeout=3000})。
     *
     * <p>给高级用户留的逃生口,UI 只收一个文本框;留空时 URL 完全由方言拼装。
     */
    private String extraParams = "";

    /** 防御性拷贝:避免把内部实例暴露给调用方后又被就地改写。 */
    public StorageConfig copy() {
        StorageConfig c = new StorageConfig();
        c.setType(type);
        c.setHost(host);
        c.setPort(port);
        c.setDatabase(database);
        c.setUsername(username);
        c.setPasswordCipher(passwordCipher);
        c.setExtraParams(extraParams);
        return c;
    }
}
