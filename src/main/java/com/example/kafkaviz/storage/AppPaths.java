package com.example.kafkaviz.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * app-data 目录定位。
 *
 * <p>目录内容的约定:三个文件互相独立,删库 / 换库互不影响。
 * <ul>
 *   <li>{@code storage.json} —— 数据源选择与外部库连接配置</li>
 *   <li>{@code secret.key} —— 凭据加密密钥(32 字节随机)</li>
 *   <li>{@code kafkaviz.db} —— 默认 SQLite 库文件</li>
 * </ul>
 *
 * <p>路径来源(按优先级):
 * <ol>
 *   <li>命令行选项 {@code --app.db-path=<dir>}(Tauri sidecar 传 OS app-data 目录);</li>
 *   <li>Spring Environment 属性 {@code app.db-path}(命令行选项其实也会进 Environment,
 *       但测试用 {@code @DynamicPropertySource} 注入时<b>只</b>走这条路 ——
 *       动态属性不会出现在 {@link ApplicationArguments} 里,只认命令行会让
 *       集成测试无法把库指到临时目录);</li>
 *   <li>缺省 {@code ./data}(相对进程工作目录)。</li>
 * </ol>
 */
@Component
public class AppPaths {

    /** 配置键名(命令行与 Environment 同名)。 */
    static final String DB_PATH_KEY = "app.db-path";
    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);
    /** 裸跑(未传 --app.db-path)时的缺省相对目录。 */
    private static final String DEFAULT_DIR = "./data";
    private final Path baseDir;

    public AppPaths(ApplicationArguments args, Environment environment) {
        this.baseDir = resolveBaseDir(args, environment);
        createDirIfNeeded(baseDir);
    }

    private static Path resolveBaseDir(ApplicationArguments args, Environment environment) {
        String configured = firstOptionValue(args, DB_PATH_KEY);
        if (configured == null || configured.isBlank()) {
            configured = environment.getProperty(DB_PATH_KEY);
        }
        if (configured == null || configured.isBlank()) {
            return Paths.get(DEFAULT_DIR).toAbsolutePath().normalize();
        }
        return Paths.get(configured.trim()).toAbsolutePath().normalize();
    }

    /**
     * 取 {@code --app.db-path=x} 的值。
     *
     * <p>用 {@link ApplicationArguments} 而不是只看 Environment:选项名含点,
     * 且需要与"未传"区分,直接查 option 语义更明确。
     */
    private static String firstOptionValue(ApplicationArguments args, String name) {
        if (args == null || !args.containsOption(name)) {
            return null;
        }
        List<String> values = args.getOptionValues(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private static void createDirIfNeeded(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // 不在这里终止进程:目录不可写时后续 SQLite 建库会给出更具体的错误,
            // 而 PostgreSQL/MySQL 模式下本地目录不可写其实无关紧要。
            log.warn("Failed to create app-data directory {} — storage may be unavailable: {}",
                    dir, e.getMessage());
        }
    }

    /** app-data 根目录(绝对路径、已规范化)。 */
    public Path baseDir() {
        return baseDir;
    }

    /** {@code storage.json} —— 数据源自举文件。 */
    public Path storageJsonFile() {
        return baseDir.resolve("storage.json");
    }

    /** {@code secret.key} —— 凭据加密密钥。 */
    public Path secretKeyFile() {
        return baseDir.resolve("secret.key");
    }

    /** {@code kafkaviz.db} —— 默认 SQLite 库文件。 */
    public Path sqliteDbFile() {
        return baseDir.resolve("kafkaviz.db");
    }

    /** 供日志/诊断展示。 */
    public String describe() {
        return baseDir.toString();
    }
}
