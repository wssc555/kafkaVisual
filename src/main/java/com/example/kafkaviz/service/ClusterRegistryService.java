package com.example.kafkaviz.service;

import com.example.kafkaviz.archive.ClusterArchiveService;
import com.example.kafkaviz.exception.ClusterNotFoundException;
import com.example.kafkaviz.kafka.AuthSpec;
import com.example.kafkaviz.kafka.AuthType;
import com.example.kafkaviz.kafka.ClusterConnectionManager;
import com.example.kafkaviz.kafka.ClusterDefinition;
import com.example.kafkaviz.model.dto.ClusterUpsertRequest;
import com.example.kafkaviz.model.vo.ClusterSummary;
import com.example.kafkaviz.model.vo.CredentialPresence;
import com.example.kafkaviz.model.vo.StorageInfo;
import com.example.kafkaviz.storage.ClusterConfigStore;
import com.example.kafkaviz.storage.FavoriteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 集群注册表编排。
 *
 * <p>把 {@link ClusterConfigStore}(配置事实源)与
 * {@link ClusterConnectionManager}(连接生命周期)编在一起,对外只暴露
 * {@link ClusterSummary}(零凭据)。
 *
 * <p>几个刻意的语义决定:
 * <ul>
 *   <li><b>PUT 改连接参数 → 断开</b>:地址/凭据变了但沿用旧连接,会让用户在
 *       "明明改了地址却还在看旧集群数据"的状态里困惑。顺序是<b>先落库后断开</b>:
 *       update 校验失败(如名称冲突)时不产生断开副作用。变更后不自动重连,
 *       由前端按需触发 {@code /connect}(或首次数据请求惰性建连)。</li>
 *   <li><b>DELETE 先断开再删配置</b>:顺序反了会留下无主客户端。</li>
 *   <li><b>archive 相关副作用</b>:DELETE 时回收归档并清理收藏;archive_enabled
 *       变化时动态启停该集群的归档器。</li>
 * </ul>
 */
@Service
public class ClusterRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ClusterRegistryService.class);

    private final ClusterConfigStore store;
    private final ClusterConnectionManager connectionManager;
    /** 归档侧副作用(表回收 / 归档器重启)由它执行。 */
    private final ClusterArchiveService archiveService;
    /** 集群删除时清理其收藏,避免留下查不到的孤儿行。 */
    private final FavoriteStore favoriteStore;
    /**
     * 认证规则校验。放在 Service 而非 Store:Store 保持"只做读写"，
     * 且同一套校验要被测连端点复用。
     */
    private final ClusterUpsertValidator validator;

    public ClusterRegistryService(ClusterConfigStore store,
                                  ClusterConnectionManager connectionManager,
                                  ClusterArchiveService archiveService,
                                  FavoriteStore favoriteStore,
                                  ClusterUpsertValidator validator) {
        this.store = store;
        this.connectionManager = connectionManager;
        this.archiveService = archiveService;
        this.favoriteStore = favoriteStore;
        this.validator = validator;
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** {@code GET /api/clusters} —— 全部集群 + 连接状态。 */
    public List<ClusterSummary> list() {
        List<ClusterDefinition> definitions = store.list();
        List<ClusterSummary> out = new ArrayList<>(definitions.size());
        for (ClusterDefinition definition : definitions) {
            out.add(toSummary(definition, connectionManager.statusOf(definition.id())));
        }
        return out;
    }

    /** {@code GET /api/clusters/status} —— 轻量状态轮询(id + 展示态 + 错误摘要)。 */
    public List<ClusterSummary> statuses() {
        List<ClusterSummary> out = new ArrayList<>();
        for (ClusterConnectionManager.ManagedStatus status : connectionManager.statuses()) {
            out.add(ClusterSummary.builder()
                    .id(status.clusterId())
                    .name(status.name())
                    .displayState(status.displayState())
                    .errorSummary(status.errorSummary())
                    .errorAt(status.errorAt())
                    .build());
        }
        return out;
    }

    /** 已提交且与库中值不同 = 变化;null / 打码哨兵 = 不修改。 */
    private static boolean secretChanged(String submitted, String stored) {
        if (submitted == null || StorageInfo.PASSWORD_MASK.equals(submitted)) {
            return false;
        }
        return !submitted.equals(stored == null ? "" : stored);
    }

    /** 该认证方式是否真的使用"传输加密开关"(MTLS 恒加密,开关无语义)。 */
    private static boolean usesTlsToggle(AuthType type) {
        return type == AuthType.NONE || type == AuthType.PASSWORD || type == AuthType.OAUTH;
    }

    /** {@code DELETE /api/clusters/{id}} —— 先断开,再删配置,最后回收归档。 */
    public void delete(long id) {
        if (store.findById(id).isEmpty()) {
            throw new ClusterNotFoundException(id);
        }
        connectionManager.disconnect(id);
        // 整表 DROP + 清 topic_registry 台账,瞬时回收(不逐行 DELETE)。
        archiveService.purgeClusterArchive(id);
        // 收藏同样要清:集群 id 不会被复用,留着只会是永远查不到也删不掉的孤儿行
        favoriteStore.removeAllOfCluster(id);
        if (!store.delete(id)) {
            throw new ClusterNotFoundException(id);
        }
        log.info("Cluster {} deleted (connection closed, archive + favorites purged)", id);
    }

    /** {@code POST /api/clusters/{id}/connect} —— 异步建连,立即返回 CONNECTING。 */
    public ClusterSummary connect(long id) {
        if (store.findById(id).isEmpty()) {
            throw new ClusterNotFoundException(id);
        }
        connectionManager.connectAsync(id);
        return toSummary(store.findById(id).orElseThrow(() -> new ClusterNotFoundException(id)),
                connectionManager.statusOf(id));
    }

    /** {@code POST /api/clusters/{id}/disconnect} —— 断开回收(归档数据保留)。 */
    public ClusterSummary disconnect(long id) {
        if (store.findById(id).isEmpty()) {
            throw new ClusterNotFoundException(id);
        }
        connectionManager.disconnect(id);
        return toSummary(store.findById(id).orElseThrow(() -> new ClusterNotFoundException(id)),
                connectionManager.statusOf(id));
    }

    // ---------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------

    /** 供功能端点/其它服务复用的存在性校验(40404)。 */
    public ClusterDefinition requireCluster(long id) {
        return store.findById(id).orElseThrow(() -> new ClusterNotFoundException(id));
    }

    /** 非秘密字段:null = 未提交(保持库中值,不算变化)。 */
    private static boolean fieldChanged(String submitted, String stored) {
        if (submitted == null) {
            return false;
        }
        return !submitted.trim().equals(stored == null ? "" : stored);
    }

    /** {@code POST /api/clusters} —— 新建(<b>不</b>自动连接)。 */
    public ClusterSummary create(ClusterUpsertRequest req) {
        // 新建:请求体即凭据全量,校验从严(证书/私钥/端点必须给齐)
        validator.validateForCreate(req);
        ClusterDefinition created = store.insert(req);
        log.info("Cluster created: id={}, name={}, authType={}",
                created.id(), created.name(), created.auth().authType());
        // 新建后不建连:地址可能还没配好,建连失败只会在 Dashboard 上留个红点,
        // 反而让用户以为"创建失败了"
        return toSummary(created, connectionManager.statusOf(created.id()));
    }

    /** {@code PUT /api/clusters/{id}} —— 修改;连接参数变化时断开(下次请求按新参数重建)。 */
    public ClusterSummary update(long id, ClusterUpsertRequest req) {
        ClusterDefinition before = store.findById(id).orElseThrow(() -> new ClusterNotFoundException(id));
        // 更新:允许省略未修改的秘密字段(三态),故校验不强制凭据齐全
        validator.validateForUpdate(req);

        boolean connectionParamsChanged = connectionParamsChanged(before, req);
        boolean archiveFlagChanged = before.archiveEnabled() != req.archiveEnabledOrDefault();

        // 先落库、后断开:update 因名称冲突等失败时,连接保持原样可用 ——
        // "改了才断",绝不出现"断了却没改成"的中间态(校验失败不该有副作用)
        ClusterDefinition updated = store.update(id, req);

        if (connectionParamsChanged) {
            // 断开但保留配置;下次请求会按新参数惰性重建
            connectionManager.disconnect(id);
        }

        if (archiveFlagChanged) {
            // archive_enabled 变化 → 动态启停该集群的归档器。
            // 连接存在时立即生效;连接不存在时下次建连按新定义决定(见 restartArchiver)。
            connectionManager.restartArchiver(id);
            log.info("Cluster {} archiveEnabled changed to {} — archiver restarted", id, updated.archiveEnabled());
        }

        log.info("Cluster {} updated (connectionParamsChanged={})", id, connectionParamsChanged);
        return toSummary(updated, connectionManager.statusOf(id));
    }

    /**
     * 连接参数是否变化。
     *
     * <p>判定范围不止地址/账号,还包括 authType、传输层开关,以及七类秘密材料。
     * 两条纪律避免误判:
     * <ul>
     *   <li><b>秘密字段按三态判定</b>:{@code null} / 打码哨兵 = 不修改(不算变化),
     *       提交了什么就与<b>库中已解密原值</b>逐字比较 —— 只"提交了同样的值"不该
     *       触发一次无谓的断开重连;</li>
     *   <li><b>非秘密字段按"是否提交"判定</b>:脚本/前端可能省略(留着库中值用),
     *       省略时不能算变化,否则只改归档开关也会把连接踢掉。</li>
     * </ul>
     */
    private boolean connectionParamsChanged(ClusterDefinition before, ClusterUpsertRequest req) {
        AuthSpec beforeAuth = before.auth();
        AuthSpec submitted = req.toAuthSpec();

        return !Objects.equals(before.bootstrapServers(), trimOrEmpty(req.getBootstrapServers()))
                || !Objects.equals(before.zkConnectString(), req.zkConnectStringOrDefault())
                || !Objects.equals(beforeAuth.authType(), submitted.authType())
                // MTLS 恒为 SSL,其 tlsEnabled 字段无语义 —— 不参与变化判定,否则"脚本省略
                // 该字段"会把一次纯改名误判成连接参数变化,白白断开一次
                || (usesTlsToggle(submitted.type()) && beforeAuth.tlsEnabled() != submitted.tlsEnabled())
                || beforeAuth.verifyHostname() != submitted.verifyHostname()
                || !Objects.equals(before.securityProtocol(), req.deriveSecurityProtocol())
                || !Objects.equals(before.saslMechanism(), req.deriveSaslMechanism())
                || !Objects.equals(before.username(), req.usernameOrDefault())
                // 秘密材料:null / 打码哨兵 → 不算变化
                || secretChanged(req.getPassword(), before.password())
                || secretChanged(req.getSslClientCertPem(), beforeAuth.sslClientCertPem())
                || secretChanged(req.getSslClientKeyPem(), beforeAuth.sslClientKeyPem())
                || secretChanged(req.getSslClientKeyPassword(), beforeAuth.sslClientKeyPassword())
                || secretChanged(req.getSslTrustCertsPem(), beforeAuth.sslTrustCertsPem())
                || secretChanged(req.getOauthClientSecret(), beforeAuth.oauthClientSecret())
                || secretChanged(req.getCustomJaas(), beforeAuth.customJaas())
                // 非秘密字段:只在真的提交了才比较
                || fieldChanged(req.getOauthTokenUrl(), beforeAuth.oauthTokenUrl())
                || fieldChanged(req.getOauthClientId(), beforeAuth.oauthClientId())
                || fieldChanged(req.getOauthScope(), beforeAuth.oauthScope())
                // 附加属性:null = 不修改;非 null 则与库中比较
                || (req.getCustomProps() != null && !beforeAuth.customProps().equals(req.getCustomProps()));
    }

    private ClusterSummary toSummary(ClusterDefinition definition,
                                     ClusterConnectionManager.ManagedStatus status) {
        AuthSpec auth = definition.auth();
        String displayState = status == null ? "OFFLINE" : status.displayState();
        String password = definition.password();
        return ClusterSummary.builder()
                .id(definition.id())
                .name(definition.name())
                .bootstrapServers(definition.bootstrapServers())
                .authType(auth.authType())
                .tlsEnabled(auth.tlsEnabled())
                .verifyHostname(auth.verifyHostname())
                .securityProtocol(definition.securityProtocol())
                .saslMechanism(definition.saslMechanism())
                .username(definition.username())
                // 秘密材料只回"有没有":明文/密文一律不出后端
                .credentialPresence(CredentialPresence.builder()
                        .password(password != null && !password.isEmpty())
                        .clientCert(auth.hasClientCert())
                        .clientKey(auth.hasClientKey())
                        .clientKeyPassword(auth.hasClientKeyPassword())
                        .trustCerts(auth.hasTrustCerts())
                        .oauthClientSecret(auth.hasOAuthClientSecret())
                        .customJaas(auth.hasCustomJaas())
                        .customProps(auth.hasCustomProps())
                        .build())
                // 非秘密字段需要回显,否则编辑对话框无法工作
                .oauthTokenUrl(auth.oauthTokenUrl())
                .oauthClientId(auth.oauthClientId())
                .oauthScope(auth.oauthScope())
                .zkConnectString(definition.zkConnectString())
                .zkAvailable(definition.zkConfigured())
                .displayState(displayState)
                .errorSummary(status == null ? null : status.errorSummary())
                .errorAt(status == null ? null : status.errorAt())
                .enabled(definition.enabled())
                .archiveEnabled(definition.archiveEnabled())
                .archiveRetentionDays(definition.archiveRetentionDays())
                .sortOrder(definition.sortOrder())
                .createdAt(definition.createdAt())
                .updatedAt(definition.updatedAt())
                .build();
    }
}
