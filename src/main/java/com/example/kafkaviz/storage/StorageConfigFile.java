package com.example.kafkaviz.storage;

import com.example.kafkaviz.security.CryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@code storage.json} 的读写(数据源自举)。
 *
 * <p>自举问题:当前启用哪个数据源这件事本身必须存在数据源之外,所以放在文件里。
 * 启动时序:读 storage.json(不存在/损坏 → 视为 SQLite)→ 建连接 → SchemaInitializer 建表。
 *
 * <p>降级口径:文件缺失是<b>正常</b>首次启动路径(DEBUG 级);文件存在但无法解析
 * 或内容非法则是<b>异常</b>配置,回退 SQLite 并 WARN —— 不静默、也不崩。
 */
@Component
public class StorageConfigFile {

    private static final Logger log = LoggerFactory.getLogger(StorageConfigFile.class);

    private final AppPaths appPaths;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    public StorageConfigFile(AppPaths appPaths, CryptoService cryptoService, ObjectMapper objectMapper) {
        this.appPaths = appPaths;
        this.cryptoService = cryptoService;
        // storage.json 是给人看得懂的配置文件,写出来要缩进
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 读取当前数据源配置。
     *
     * <p>文件不存在 → SQLite 默认;解析失败 / 类型非法 → SQLite 默认 + WARN(不抛出)。
     * 这一条"永不抛异常"的承诺很重要:读配置失败若中断启动,用户将没有任何 UI
     * 入口去修这个问题(app-data 目录下的文件只能手动改)。
     */
    public StorageConfig read() {
        Path file = appPaths.storageJsonFile();
        if (!Files.exists(file)) {
            log.debug("storage.json not found at {} — defaulting to SQLite", file);
            return new StorageConfig();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                log.warn("storage.json at {} is empty — defaulting to SQLite", file);
                return new StorageConfig();
            }
            StorageConfig config = objectMapper.readValue(json, StorageConfig.class);
            if (config == null) {
                log.warn("storage.json at {} parsed to null — defaulting to SQLite", file);
                return new StorageConfig();
            }
            if (config.getType() == null) {
                log.warn("storage.json at {} has no 'type' — defaulting to SQLite", file);
                return new StorageConfig();
            }
            log.info("Loaded storage configuration: type={} {}", config.getType().configValue(),
                    config.getType() == StorageType.SQLITE ? "(app-data)" : "database=" + config.getDatabase());
            return config;
        } catch (Exception e) {
            log.warn("Failed to parse storage.json at {} — defaulting to SQLite. "
                    + "Fix or delete the file to silence this warning. Cause: {}", file, e.getMessage());
            return new StorageConfig();
        }
    }

    /** 当前文件名,供日志/响应展示。 */
    public String fileName() {
        return appPaths.storageJsonFile().toString();
    }

    /**
     * 落盘。
     *
     * <p><b>入参约定</b>:{@code config.passwordCipher} 此时必须<b>已经是密文</b>
     * (由 {@link #encryptPassword(String)} 或 {@link #writeWithPlainPassword} 产生)。
     * 明文口令永不通过本方法落盘。
     */
    public void write(StorageConfig config) {
        Path file = appPaths.storageJsonFile();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // 先写临时文件再原子替换:避免写到一半断电留下半截 JSON,
            // 下次启动读解析失败会静默降级 SQLite(对用户表现为"配置丢了")。
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(config), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Wrote storage configuration to {} (type={})", file, config.getType().configValue());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write storage.json at " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * 便捷入口:把明文口令加密后与配置一起落盘(写回时加密)。
     *
     * @param plainPassword 明文口令;null / 空 → 落盘为空(无口令)
     */
    public void writeWithPlainPassword(StorageConfig config, String plainPassword) {
        StorageConfig toWrite = config.copy();
        toWrite.setPasswordCipher(encryptPassword(plainPassword));
        write(toWrite);
    }

    /** 明文 → 密文(Base64)。 */
    public String encryptPassword(String plainPassword) {
        return cryptoService.encrypt(plainPassword);
    }

    /**
     * 密文 → 明文。
     *
     * <p>解不开(换过 secret.key、文件被改坏)时抛出 {@link IllegalStateException}:
     * 调用方(集群注册表 / 数据源装配)应当把它当作该条配置不可用处理并如实报错,
     * 绝不能拿空口令悄悄去连 —— 那会表现为"认证失败"这种误导性错误。
     */
    public String decryptPassword(String passwordCipher) {
        return cryptoService.decrypt(passwordCipher);
    }

    /** 供 Diagnostics:文件是否存在。 */
    public boolean exists() {
        return Files.exists(appPaths.storageJsonFile());
    }
}
