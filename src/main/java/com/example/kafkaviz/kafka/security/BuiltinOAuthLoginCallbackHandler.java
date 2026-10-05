package com.example.kafkaviz.kafka.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置 OAuth2 登录回调处理器。
 *
 * <p>用途:让"OAuth2(统一身份)"在桌面场景<b>开箱可用</b> —— 用户只需填
 * token 端点 + client id/secret,不必自己写 Java 类、也不必把外部 jar 塞进 classpath。
 * 由 {@link OAuthConfigurer} 通过 {@code sasl.login.callback.handler.class} 挂到
 * 每个 Kafka 客户端上(AdminClient / Producer / Consumer 共用同一份配置)。
 *
 * <p>职责:
 * <ol>
 *   <li>处理 {@link OAuthBearerTokenCallback},按 {@code client_credentials} 授权
 *       向 token 端点换取 access token;</li>
 *   <li>缓存 token 并在过期前提前刷新({@value #REFRESH_MARGIN_MS} ms 余量);</li>
 *   <li>并发合并:同一份凭据被多个客户端同时登录时只打一次端点。</li>
 * </ol>
 *
 * <p><b>安全口径</b>:异常消息与日志里只出现 token 端点 URL 与 HTTP 状态码,
 * <b>绝不出现</b> client secret 或 token 值(缓存键是凭据的 SHA-256,不是明文凭据)。
 *
 * <p><b>与 Kafka 内建刷新的关系</b>:Kafka 侧还有一层
 * {@code OAuthBearerRefreshingLogin}(依据 token 的 {@code lifetimeMs} 定时重新回调
 * 本 handler)。本类的缓存因此是"第二层防御":即使 Kafka 提前回调,也不会产生
 * 重复的端点请求。
 *
 * <p>线程安全:handler 会被多个客户端分别 {@code configure},而缓存是静态共享的 ——
 * 所有共享状态只有 {@link #TOKEN_CACHE}(ConcurrentHashMap)与一把取 token 的锁,
 * 取 token 属低频操作,全局锁足够且最不容易写错。
 */
public class BuiltinOAuthLoginCallbackHandler implements AuthenticateCallbackHandler {

    /**
     * 自定义配置键前缀。
     *
     * <p>刻意<b>不</b>复用 Kafka 的 {@code sasl.oauthbearer.*} 键:那些键有 Kafka 自己的
     * 语义(例如 {@code sasl.oauthbearer.token.endpoint.url} 只在 secured 实现里生效),
     * 混用会让"这个键到底谁在读"变得不可知。这里的键由 {@link OAuthConfigurer} 写入、
     * 由本类读取,闭环且可 grep。
     */
    public static final String CONFIG_PREFIX = "kafkaviz.oauth.";

    public static final String TOKEN_URL_CONFIG = CONFIG_PREFIX + "token-url";

    public static final String CLIENT_ID_CONFIG = CONFIG_PREFIX + "client-id";

    public static final String CLIENT_SECRET_CONFIG = CONFIG_PREFIX + "client-secret";

    public static final String SCOPE_CONFIG = CONFIG_PREFIX + "scope";

    /** token 有效期缺省值(端点未回 {@code expires_in} 时使用)。 */
    private static final long DEFAULT_LIFETIME_MS = 3600_000L;

    /** 过期余量:剩这么少就认为不可用,提前换新的(避免"取出来就过期")。 */
    private static final long REFRESH_MARGIN_MS = 30_000L;

    /** HTTP 连接/请求超时:与项目"单次阻塞 10s 上限"的纪律一致。 */
    private static final long HTTP_TIMEOUT_MS = 10_000L;

    /** 端点报错时回显 body 的最大长度(错误细节有用,但不能把响应体整段倒进日志)。 */
    private static final int ERROR_BODY_MAX = 200;

    private static final Logger log = LoggerFactory.getLogger(BuiltinOAuthLoginCallbackHandler.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 静态缓存:同一凭据被多个客户端登录时共享。 */
    private static final Map<String, Token> TOKEN_CACHE = new ConcurrentHashMap<>();

    /** 取 token 的全局锁(见类注释:低频操作,合并并发请求优先于最大化并行度)。 */
    private static final Object FETCH_LOCK = new Object();

    private String tokenUrl = "";

    private String clientId = "";

    private String clientSecret = "";

    private String scope = "";

    private HttpClient httpClient;

    /** 供 Kafka 反射实例化(必须保留公开无参构造)。 */
    public BuiltinOAuthLoginCallbackHandler() {
    }

    private static boolean usable(Token token) {
        return token != null && System.currentTimeMillis() < token.lifetimeMs() - REFRESH_MARGIN_MS;
    }

    /** scope 是空格分隔的字符串(RFC 6749)。 */
    private static Set<String> parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String part : raw.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return Set.copyOf(out);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 必备算法,走到这里说明运行环境异常
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---------------------------------------------------------------
    // token 获取与缓存
    // ---------------------------------------------------------------

    private static String readConfig(Map<String, ?> configs, String key) {
        return readRawConfig(configs, key).trim();
    }

    /** 原样取值(不 trim),用于秘密材料。 */
    private static String readRawConfig(Map<String, ?> configs, String key) {
        Object value = configs == null ? null : configs.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static void requireNonBlank(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required OAuth configuration '" + key
                    + "' (set by OAuthConfigurer from the cluster definition)");
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "<empty>";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= ERROR_BODY_MAX ? flat : flat.substring(0, ERROR_BODY_MAX) + "...";
    }

    @Override
    public void configure(Map<String, ?> configs, String saslMechanism, List<AppConfigurationEntry> jaasConfigEntries) {
        this.tokenUrl = readConfig(configs, TOKEN_URL_CONFIG);
        this.clientId = readConfig(configs, CLIENT_ID_CONFIG);
        // secret 刻意不 trim:凭据里的首尾空白是内容的一部分,裁掉会得到"认证失败"这种误导性结果
        this.clientSecret = readRawConfig(configs, CLIENT_SECRET_CONFIG);
        this.scope = readConfig(configs, SCOPE_CONFIG);

        // fail fast:缺键在这里就报明确原因,而不是等建连时抛一个"认证失败"的模糊异常
        requireNonBlank(tokenUrl, TOKEN_URL_CONFIG);
        requireNonBlank(clientId, CLIENT_ID_CONFIG);
        requireNonBlank(clientSecret, CLIENT_SECRET_CONFIG);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                .build();
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (callback instanceof OAuthBearerTokenCallback tokenCallback) {
                tokenCallback.token(fetchOrRefresh());
            } else {
                // 与 Kafka 自带 handler 同口径:未知回调显式抛错而不是静默忽略 ——
                // 静默会让配置错误表现为"认证超时",排查代价高得多
                throw new UnsupportedCallbackException(callback,
                        "Unsupported callback for OAUTHBEARER login: " + callback.getClass().getName());
            }
        }
    }

    @Override
    public void close() {
        // 无可释放资源:HttpClient 不需要关闭,缓存跨登录周期有意保留
    }

    private Token fetchOrRefresh() throws IOException {
        String key = cacheKey();
        Token cached = TOKEN_CACHE.get(key);
        if (usable(cached)) {
            return cached;
        }
        synchronized (FETCH_LOCK) {
            // 双重检查:等待锁期间可能已被别的客户端填好
            cached = TOKEN_CACHE.get(key);
            if (usable(cached)) {
                return cached;
            }
            Token fresh = fetchToken();
            TOKEN_CACHE.put(key, fresh);
            return fresh;
        }
    }

    /** {@code client_credentials} 换取 access token。 */
    private Token fetchToken() throws IOException {
        String form = "grant_type=client_credentials"
                + (scope.isEmpty() ? "" : "&scope=" + urlEncode(scope));
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OAuth token request interrupted: " + tokenUrl, e);
        } catch (IOException e) {
            // 只带 URL,不带任何凭据
            throw new IOException("Failed to reach OAuth token endpoint " + tokenUrl + ": " + e.getMessage(), e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OAuth token endpoint " + tokenUrl + " returned HTTP "
                    + response.statusCode() + ": " + truncate(response.body()));
        }

        Token token = parseToken(response.body());
        log.info("Obtained OAuth access token from {} (principal={}, lifetimeMs={})",
                tokenUrl, token.principalName(), token.lifetimeMs() - System.currentTimeMillis());
        return token;
    }

    private Token parseToken(String body) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new IOException("OAuth token endpoint " + tokenUrl
                    + " returned a non-JSON response: " + truncate(body), e);
        }

        String accessToken = root.path("access_token").asText("");
        if (accessToken.isEmpty()) {
            // 不把 body 整段带出:某些实现在错误响应里会回显请求参数
            throw new IOException("OAuth token endpoint " + tokenUrl
                    + " response has no access_token: " + truncate(body));
        }

        long expiresInSeconds = root.path("expires_in").asLong(0L);
        long lifetimeMs = System.currentTimeMillis()
                + (expiresInSeconds > 0 ? expiresInSeconds * 1000L : DEFAULT_LIFETIME_MS);

        // 响应里的 scope 优先(端点可能裁剪/扩权),缺省回落到配置值
        String effectiveScope = root.path("scope").asText("");
        if (effectiveScope.isEmpty()) {
            effectiveScope = scope;
        }

        return new Token(accessToken, lifetimeMs, clientId, parseScope(effectiveScope));
    }

    /**
     * 缓存键 = 凭据的摘要。
     *
     * <p>把 client secret 一并摘要进去是刻意的:同一端点+client id 改了 secret 之后,
     * 旧 token 不应该继续复用。摘要而非明文则保证键里不含秘密(键可能出现在
     * 内存转储/调试打印里)。
     */
    private String cacheKey() {
        return sha256Hex(tokenUrl + "\n" + clientId + "\n" + scope + "\n" + sha256Hex(clientSecret));
    }

    /**
     * 交给 Kafka 的 token 对象。
     *
     * <p>{@link #startTimeMs()} 返回 {@code null}:"起始时刻未知"是接口明确允许的取值
     * (Kafka 只用 {@code lifetimeMs} 排刷新计划),编造一个时间反而会误导刷新逻辑。
     */
    private static final class Token implements OAuthBearerToken {

        private final String value;

        private final long lifetimeMs;

        private final String principalName;

        private final Set<String> scope;

        private Token(String value, long lifetimeMs, String principalName, Set<String> scope) {
            this.value = value;
            this.lifetimeMs = lifetimeMs;
            this.principalName = principalName;
            this.scope = scope;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public Long startTimeMs() {
            return null;
        }

        @Override
        public long lifetimeMs() {
            return lifetimeMs;
        }

        @Override
        public String principalName() {
            return principalName;
        }

        @Override
        public Set<String> scope() {
            return scope;
        }
    }
}
