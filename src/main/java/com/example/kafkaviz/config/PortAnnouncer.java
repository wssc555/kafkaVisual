package com.example.kafkaviz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 桌面(Tauri sidecar)模式端口握手。
 *
 * <p><b>协议</b>:Rust 父进程以 {@code --server.port=0 --announce-port} 拉起后端;
 * 内嵌 web server 绑定端口后往 <b>stdout</b> 打印一行 {@code PORT=<port>} 并立即 flush。
 *
 * <p>Spring 的 banner 与启动日志会先于本行出现在 stdout,
 * 因此 Rust 侧<b>逐行扫描</b> stdout 找 {@code PORT=} 前缀,兼容日志噪声。
 *
 * <p><b>开关</b>:仅当命令行含 {@code --announce-port}(无值或值不为 {@code false})时输出;
 * 裸跑 / 开发模式零输出,stdout 行为不受影响。{@code BaseApiIT} 不传该选项,
 * 共享测试上下文不受影响。
 */
@Component
public class PortAnnouncer implements ApplicationListener<WebServerInitializedEvent> {

    /** 命令行开关名(与 Rust 侧启动参数约定一致)。 */
    static final String ANNOUNCE_FLAG = "announce-port";

    private static final Logger log = LoggerFactory.getLogger(PortAnnouncer.class);

    private final ApplicationArguments args;

    public PortAnnouncer(ApplicationArguments args) {
        this.args = args;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (!enabled()) {
            return;
        }
        int port = event.getWebServer().getPort();
        // 协议行必须走 stdout 并 flush;常规日志仍走 slf4j(文件/stderr),不污染握手扫描。
        System.out.println("PORT=" + port);
        System.out.flush();
        log.info("Announced sidecar port {} on stdout", port);
    }

    /**
     * {@code --announce-port} 无值(空 List)或 {@code =true} 均算开启;
     * 显式 {@code =false} 关闭。对 {@code getOptionValues} 返回 null 的可能性一并防御
     * (与 {@code AppPaths.firstOptionValue} 同款防御式写法)。
     */
    private boolean enabled() {
        if (args == null || !args.containsOption(ANNOUNCE_FLAG)) {
            return false;
        }
        List<String> values = args.getOptionValues(ANNOUNCE_FLAG);
        return values == null || values.isEmpty() || !"false".equalsIgnoreCase(values.get(0));
    }
}
