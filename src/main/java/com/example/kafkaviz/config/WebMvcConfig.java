package com.example.kafkaviz.config;

import com.example.kafkaviz.kafka.ClusterConnectionManager;
import com.example.kafkaviz.web.ClusterArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * MVC 扩展点注册。
 *
 * <p>注册两个:
 * <ul>
 *   <li>{@link ClusterArgumentResolver} —— 把 {@code /api/c/{clusterId}/...} 的集群段
 *       解析成 {@code ClusterConnection} / {@code ClusterDefinition} 注入控制器方法参数;</li>
 *   <li>CORS 白名单 —— Tauri 桌面模式下前端直连 {@code http://127.0.0.1:<随机端口>},
 *       WebView origin 因平台而异:Windows/Linux WebView2 为
 *       {@code http://tauri.localhost},macOS WKWebView 为 {@code tauri://localhost}。
 *       浏览器开发(vite 5173)本走 proxy 无跨域,列在这里仅为直连后端调试兜底。</li>
 * </ul>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ClusterConnectionManager connectionManager;

    public WebMvcConfig(ClusterConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new ClusterArgumentResolver(connectionManager));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://tauri.localhost", "tauri://localhost", "http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
