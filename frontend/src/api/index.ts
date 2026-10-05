import axios from 'axios'
import {bootLog} from '../composables/bootLog'
import {i18n} from '../i18n'

const api = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

/**
 * Tauri 桌面模式 baseURL 握手。
 *
 * - 浏览器 / vite dev：维持默认 '/api'（vite proxy → 8080），返回 []；
 * - Tauri：轮询 `invoke('backend_port')` 拿 sidecar 实际监听端口，
 *   把 baseURL 改指 `http://127.0.0.1:{port}/api`。
 *
 * 必须轮询：Spring Boot 从 spawn 到打印 PORT= 需要 5–30s，启动瞬间单次 invoke
 * 必然拿到 null（这正是首版「仪表盘空白」的根因——baseURL 永久落在 '/api'）。
 * 拿到端口立即返回 []; 60s 超时说明 sidecar 起失败 —— 拉取壳侧收集的
 * startup_errors 一并返回,由调用方（main.ts）弹框展示,同时保持 '/api' 兜底挂载。
 *
 * @returns 启动期错误列表（空数组 = 一切正常）
 */
export const initApiBaseUrl = async (): Promise<string[]> => {
  // 用 globalThis 而非 window:vitest(node 环境)下没有 window,直接访问会 ReferenceError
  const tauri = (globalThis as unknown as { __TAURI_INTERNALS__?: { invoke?: (cmd: string) => Promise<unknown> } }).__TAURI_INTERNALS__
  if (!tauri || typeof tauri.invoke !== 'function') {
    bootLog('浏览器/开发模式:跳过握手,走 vite proxy → 8080')
    return []
  }
  bootLog('桌面模式:开始轮询 sidecar 端口(最长 60s)')
  for (let attempt = 0; attempt < 120; attempt++) {
    try {
      const port = Number(await tauri.invoke('backend_port'))
      if (Number.isInteger(port) && port > 0 && port < 65536) {
        api.defaults.baseURL = `http://127.0.0.1:${port}/api`
        bootLog(`握手成功:baseURL = http://127.0.0.1:${port}/api`)
        return []
      }
    } catch (e: any) {
      bootLog(`第 ${attempt + 1} 次握手异常:${e?.message ?? e}(继续重试)`)
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  bootLog('握手超时(60s):后端服务未就绪')
  // 60s 超时:sidecar 没起来。拉取壳侧记录的启动错误(旧版壳无此 command 时兜底空数组)
  let errors: string[] = []
  try {
    const raw = await tauri.invoke('startup_errors')
    if (Array.isArray(raw)) errors = raw.map(String)
  } catch {
    // 忽略,走通用文案
  }
  if (errors.length === 0) {
    errors.push(i18n.global.t('app.startup.notReadyIn60s'))
  }
  return errors
}

/**
 * 统一响应信封。后端 @JsonInclude(NON_NULL) 会省略 null 字段，
 * 因此 data 内部的可选字段用 `?` 表达，运行时值为 undefined 而非 null。
 */
export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

/**
 * 携带业务错误码与 HTTP 状态的错误。拦截器把 err.response.data.code / status
 * 挂回来，让组件层能按码分支（如 40402 / 50301 / 40404），而不是只拿 message。
 *
 * 现有消费点（`grep "instanceof ApiError"` 可复核）：
 * - `views/TopicDetail.vue`：40001 且 msg 含 `earliest available offset is N` 时引导从该 offset 重查
 * - `views/TopicDetail.vue`：40401 加载失败时给出「返回集群」引导而非只允许重试
 * - `views/ClusterManage.vue` / `views/Settings.vue`：按 code 区分 40404（集群不存在）与 40001（参数/校验失败）
 *
 * 注意：后端不存在「200 + code≠0」路径（GlobalExceptionHandler 恒伴随错误状态码），
 * 因此 success 路径无需再判 code。
 */
export class ApiError extends Error {
  code?: number
  status?: number
  constructor(message: string, code?: number, status?: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

api.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const msg = err.response?.data?.msg || err.message || 'Request failed'
    // 保留业务错误码与 HTTP 状态，供组件按码分支
    const code = err.response?.data?.code
    const status = err.response?.status
    return Promise.reject(new ApiError(msg, code, status))
  },
)

// ---- Types ----

export interface BrokerInfo {
  id: number
  host: string
  port: number
  /**
   * 未配置 rack 时后端**显式输出 null**（BrokerInfo 无 @JsonInclude(NON_NULL)，
   * 只有 ApiResponse / MessageRecord / PartitionOffsetLookup.PartitionOffset 带该注解），
   * 因此这里按「值可能为 null」消费，而不是「字段可能不存在」。
   */
  rack?: string | null
}

export interface ClusterInfo {
  clusterId: string
  controllerId: number
  brokers: BrokerInfo[]
}

export interface PartitionInfo {
  partition: number
  /** 分区离线 / leader 迁移中时无 leader，后端返回 -1，前端显示「离线」 */
  leader: number
  replicas: number[]
  isr: number[]
  beginningOffset: number
  endOffset: number
}

export interface TopicDetail {
  name: string
  partitions: PartitionInfo[]
}

export interface CreateTopicRequest {
  name: string
  partitions: number
  replicationFactor: number
  configs?: Record<string, string>
}

export interface TopicActionResult {
  name: string
  created?: boolean
  deleted?: boolean
}

export interface MessageRecord {
  topic: string
  partition: number
  offset: number
  timestamp: number
  timestampType: string
  /** 后端省略 key 时运行时值为 undefined，非 null */
  key?: string | null
  value?: string | null
  valueFormatted?: string | null
  headers: Record<string, string>
}

export interface MessageQueryResult {
  topic: string
  partition: number
  startOffset: number
  records: MessageRecord[]
  totalReturned: number
  endOffset: number
  hasMore: boolean
}

export interface PartitionOffset {
  partition: number
  currentOffset: number
  logEndOffset: number
  lag: number
  memberId?: string | null
  clientId?: string | null
  host?: string | null
}

export interface ConsumerGroupDetail {
  group: string
  topic: string
  state: string
  partitions: PartitionOffset[]
  totalLag: number
}

export interface PartitionReset {
  partition: number
  offset: number
}

export interface ZkNodeStat {
  czxid: number
  mzxid: number
  ctime: number
  mtime: number
  version: number
  cversion: number
  aversion: number
  ephemeralOwner: number
  dataLength: number
  numChildren: number
  pzxid: number
}

export interface ZkNode {
  path: string
  stat?: ZkNodeStat
  children?: ZkNode[]
}

export interface ZkChildrenResult {
  path: string
  stat?: ZkNodeStat
  children: ZkNode[]
  /**
   * 递归列出被上限截断的标记。
   * true = 结果因 zk.max-recursive-depth / zk.max-recursive-nodes 被截断，
   * 返回的子树**不完整**（但仍是最外层 200 + code=0，不是错误）；
   * 未截断时后端显式输出 null（该 VO 无 @JsonInclude(NON_NULL)）。
   * 因此消费方必须用 `=== true` 判定，不能依赖字段存在性。
   */
  truncated?: boolean | null
}

export interface DeleteResult {
  path: string
  deleted: boolean
}

export interface ClusterMode {
  mode: 'ZOOKEEPER' | 'KRAFT'
  zkAvailable: boolean
}

export interface BrokerConfig {
  brokerId: number
  configs: Record<string, string>
}

export interface TopicConfig {
  topic: string
  configs: Record<string, string>
}

export interface AclInfo {
  principal: string
  host: string
  operation: string
  permissionType: string
  resourceType: string
  resourceName: string
}

export interface PartitionLogInfo {
  topic: string
  partition: number
  size: number
  brokerId: number
  future: boolean
}

export interface LogDirInfo {
  brokerId: number
  error?: string | null
  partitions: PartitionLogInfo[]
}

/**
 * 单个 broker 上单个 log dir 的汇总（不含逐分区明细）。
 * 大集群下逐分区明细可达上万行、JSON 数十 MB，默认视图只给汇总。
 */
export interface LogDirSummary {
  brokerId: number
  logDir: string | null
  totalSize: number
  partitionCount: number
  /** 该 log dir 出错时的错误信息；正常时后端**显式输出 null**（无 @JsonInclude(NON_NULL)） */
  error?: string | null
}

// ---- Dashboard ----

export interface DashboardOverview {
  clusterId: string
  mode: 'ZOOKEEPER' | 'KRAFT'
  controllerId: number
  brokerCount: number
  topicCount: number
  internalTopicCount: number
  partitionCount: number
  underReplicatedPartitions: number
  offlinePartitions: number
  totalLogSizeBytes: number
  consumerGroupCount: number
}

/**
 * GET /dashboard/multi-overview 的单集群卡片（跨集群聚合，不带集群段）。
 *
 * - 在线集群：overview 有值，archivedMessages 为 null（不做每 10s 一次的 COUNT(*)）；
 * - 离线 / 连接中集群：overview 为 null，archivedMessages 为本地归档条数
 *   （归档量查询失败时为 -1，前端按「获取失败」渲染）；
 * - 单集群指标查询失败：该卡片 errorSummary 有值、overview 为 null，不拖垮其它卡片。
 */
export interface MultiClusterDashboard {
  clusterId: number
  name: string
  displayState: 'ONLINE' | 'OFFLINE' | 'CONNECTING'
  overview?: DashboardOverview | null
  errorSummary?: string | null
  archivedMessages?: number | null
}

// ---- 集群注册表 ----

export type ClusterDisplayState = 'ONLINE' | 'OFFLINE' | 'CONNECTING'

/**
 * 认证方式判别符（后端 `kafka/AuthType`）。
 *
 * - `NONE`：无认证（`tlsEnabled` 决定是否只做 TLS 加密）；
 * - `PASSWORD`：用户名口令（SASL/PLAIN 或 SCRAM-SHA-256/512）；
 * - `MTLS`：双向 TLS（客户端证书 + 私钥 PEM，可选 CA）；
 * - `OAUTH`：SASL/OAUTHBEARER + 内置 token 端点客户端（固定 client_credentials）；
 * - `CUSTOM`：逃生舱（手选协议/机制 + 完整 JAAS + 附加客户端属性）。
 *
 * 后端兼容策略是「入站反推、出站物化」：请求不带 authType 时按旧字段反推，
 * 响应永远带上 authType 与派生好的 securityProtocol/saslMechanism。
 */
export type ClusterAuthType = 'NONE' | 'PASSWORD' | 'MTLS' | 'OAUTH' | 'CUSTOM'

/**
 * 认证材料的"存在性"位（后端 `CredentialPresence`）。
 *
 * 秘密材料（私钥 / client secret / JAAS / 附加属性）**永不回传**明文或密文，
 * 只回是否已配置 —— 编辑对话框据此渲染"已配置，留空表示不修改"的占位。
 */
export interface CredentialPresence {
  password: boolean
  clientCert: boolean
  clientKey: boolean
  clientKeyPassword: boolean
  trustCerts: boolean
  oauthClientSecret: boolean
  customJaas: boolean
  customProps: boolean
}

/**
 * GET /api/clusters 与 GET /api/clusters/status 的列表项。
 *
 * 注意口径：
 * `/clusters/status` 与 `DELETE /clusters/{id}` 返回的是**部分摘要**——
 * 未填字段为 null / false / 0 占位，前端**不得**据此判断 archiveEnabled / zkAvailable；
 * 只有 `GET /api/clusters` 全量列表（loadClusters）的结果可以。
 */
export interface ClusterSummary {
  id: number
  name: string
  bootstrapServers?: string
  /** 认证方式判别符（旧库经迁移回填，故恒有值）；status 轻量端点不含 */
  authType?: ClusterAuthType
  /** 传输加密开关（NONE/PASSWORD/OAUTH 有效；MTLS 恒加密） */
  tlsEnabled?: boolean
  /** 是否校验服务器主机名（SSL/SASL_SSL 有效） */
  verifyHostname?: boolean
  /**
   * 物化派生列（由 authType + tlsEnabled 算出，不再是用户直接提交值）：
   * 空 = 无认证 / PLAINTEXT(不产生本值) / SSL / SASL_PLAINTEXT / SASL_SSL
   */
  securityProtocol?: string
  /** 物化派生列：PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512 / OAUTHBEARER / 空 */
  saslMechanism?: string
  username?: string
  /** 秘密材料是否已配置（值本身永不回传） */
  credentialPresence?: CredentialPresence
  /** OAuth token 端点（非秘密，需回显；未配置时后端给空串） */
  oauthTokenUrl?: string | null
  oauthClientId?: string | null
  oauthScope?: string | null
  /** 空 = 该集群 KRaft 模式 */
  zkConnectString?: string
  /** 该集群是否配置了 ZK（编辑回显用；status 轻量端点不含） */
  zkAvailable?: boolean
  displayState: ClusterDisplayState
  /** 最近一次失败摘要；非 ERROR 态为 null */
  errorSummary?: string | null
  /** 最近一次失败时刻（ISO-8601）；非 ERROR 态为 null */
  errorAt?: string | null
  enabled?: boolean
  archiveEnabled?: boolean
  archiveRetentionDays?: number
  sortOrder?: number
  createdAt?: string
  updatedAt?: string
}

/**
 * POST /api/clusters 与 PUT /api/clusters/{id} 请求体。
 *
 * **秘密字段三态**（后端 ClusterUpsertRequest 契约，避免"没动某个框就把凭据清空"）。
 * 适用于 password 及全部新增秘密材料（client 证书/私钥/私钥口令/CA/clientSecret/JAAS）：
 * - `undefined`（字段缺省）—— 保持库中原值；编辑时未修改的字段必须省略；
 * - `'******'` —— 同上（对打码哨兵的防御）；
 * - `''`（空串）—— 显式清空；
 * - 其他 —— 作为新值加密落库。
 *
 * `customProps` 略有不同：`undefined` = 不修改；`{}` = 清空；非空对象 = 整体替换。
 *
 * **securityProtocol / saslMechanism 的语义变化**：除 CUSTOM 外，它们由前端提交值
 * 降级为忽略项 —— 落库值由后端按 authType 派生（见 clusterForm.ts 的派生对照）。
 */
export interface ClusterUpsertPayload {
  name: string
  bootstrapServers: string
  /** 认证方式；缺省时后端按 securityProtocol 反推（旧客户端兼容） */
  authType?: ClusterAuthType
  /** NONE/PASSWORD/OAUTH 的传输加密开关；缺省由 securityProtocol 反推 */
  tlsEnabled?: boolean
  /** 是否校验服务器主机名；缺省 true（自签证书 CN 不匹配时需关闭） */
  verifyHostname?: boolean
  /** MTLS：客户端证书链 PEM */
  sslClientCertPem?: string
  /** MTLS：PKCS#8 私钥 PEM（加密私钥则必须同时给口令） */
  sslClientKeyPem?: string
  /** MTLS：私钥口令（仅加密私钥需要） */
  sslClientKeyPassword?: string
  /** MTLS：CA 证书 PEM；留空 = 使用 JVM 默认信任库 */
  sslTrustCertsPem?: string
  /** OAUTH：token 端点 */
  oauthTokenUrl?: string
  oauthClientId?: string
  oauthClientSecret?: string
  oauthScope?: string
  /** CUSTOM：完整 JAAS 串（原文生效，不转义） */
  customJaas?: string
  /** CUSTOM：附加客户端属性；null 值非法（后端 40001） */
  customProps?: Record<string, string>
  /** CUSTOM 时作为手选协议；其余类型忽略 */
  securityProtocol?: string
  saslMechanism?: string
  username?: string
  password?: string
  /** 空 = 该集群 KRaft 模式 */
  zkConnectString?: string
  enabled?: boolean
  archiveEnabled?: boolean
  archiveRetentionDays?: number
  sortOrder?: number
}

/**
 * POST /api/clusters/validate 请求体：与 upsert 同构，多一个 `clusterId`。
 *
 * 提交 `clusterId` 时，留空（undefined 或 `'******'`）的秘密字段由服务端从库中补全
 * —— 编辑已有集群时不必重输口令/私钥即可测连。
 */
export interface ClusterValidatePayload extends ClusterUpsertPayload {
  clusterId?: number
}

/** POST /api/clusters/validate 成功结果（失败走 40001/50302/50001 错误码，不进本对象） */
export interface ClusterValidateResult {
  ok: boolean
  /** 探测到的 broker 数量 */
  brokerCount: number
  /** 建连 + describeCluster 耗时(ms) */
  elapsedMs: number
}

// ---- 数据源配置 ----

/** GET /api/storage 的回显（password 有值时固定为打码哨兵 '******'，永不回明文） */
export interface StorageInfo {
  type: 'sqlite' | 'postgresql' | 'mysql'
  host?: string | null
  port?: number | null
  database?: string | null
  username?: string | null
  password?: string | null
  extraParams?: string | null
  /** 当前 Spring 上下文实际生效的类型；与 type 不一致 = 已保存未重启 */
  activeType?: string | null
  /** type != activeType 的便捷标记，直接用于「已保存未生效」横幅 */
  restartRequired?: boolean
  /** storage.json 的绝对路径，便于用户手动排查 */
  configFile?: string | null
}

/** PUT /api/storage 与 POST /api/storage/test 请求体；password 三态语义同 ClusterUpsertPayload */
export interface StorageConfigPayload {
  type: string
  host?: string
  /** 0 或缺省时用方言默认端口（PG 5432 / MySQL 3306） */
  port?: number
  database?: string
  username?: string
  password?: string
  /** 追加到 JDBC URL 的高级参数，形如 `sslMode=REQUIRED` */
  extraParams?: string
}

/** POST /api/storage/test 结果；失败是正常业务结果（200 + success=false），不是 5xx */
export interface StorageTestResult {
  success: boolean
  /** 建连 + SELECT 1 的总耗时(ms)；失败时表示失败前耗时 */
  latencyMs?: number | null
  version?: string | null
  error?: string | null
}

// ---- 历史消息归档 ----

/**
 * GET /api/c/{id}/archive/topics 列表项。
 *
 * 注意：`deleted` 是 **int 0/1**（不是 boolean），消费方须按数值判定；
 * 字段名是 `topicName`（不是 `topic`）。
 */
export interface ArchiveTopicSummary {
  topicName: string
  /** 1 = 已从 Kafka 消失（仅归档可查）；0 = 仍在 Kafka topic 列表中 */
  deleted: number
  /** 首次归档时间（ISO-8601） */
  firstSeenAt: string
  /** 最近一次「仍在 Kafka 列表中」的时间；被删后不再更新 */
  lastSeenAt: string
  /** 已归档消息条数 */
  messageCount: number
  /** 最近一次记录的分区数；首见未采集时为 null */
  lastPartitionCount?: number | null
}

export interface ArchiveMessageQueryParams {
  topic: string
  partition?: number
  /** 毫秒时间戳下界（含） */
  fromTime?: number
  /** 毫秒时间戳上界（含） */
  toTime?: number
  /**
   * 翻页游标的分区分量。**未指定分区查询时，翻页必须与 offsetFrom 成对回传**
   * 上一页的 nextPartition / nextOffset，否则多分区 topic 会漏行；
   * 指定分区时只传 offsetFrom 即可。
   */
  offsetFromPartition?: number
  /** 翻页游标的 offset 分量（含） */
  offsetFrom?: number
  /** 缺省 100，上限 1000（后端 40001） */
  limit?: number
}

/**
 * GET /api/c/{id}/archive/messages 结果。records 复用 MessageRecord，
 * MessageTable / MessageDetail 零适配渲染。
 */
export interface ArchiveMessageQueryResult {
  topic: string
  /** 查询时指定的分区；null = 全部分区 */
  partition?: number | null
  totalReturned: number
  records: MessageRecord[]
  /** 判定方式 = 返回条数 == limit */
  hasMore: boolean
  /** 下一页游标 offset 分量 = 末条 offset + 1；空结果时原样回传请求值 */
  nextOffset: number
  /** 下一页游标 partition 分量；空结果时原样回传请求值 */
  nextPartition?: number | null
}

// ---- 收藏 / 偏好 ----

export interface FavoriteItem {
  clusterId: number
  /** 'topic' 或 'group' */
  itemType: string
  itemName: string
}

// ---- 按时间戳定位 ----

export interface PartitionOffsetLookup {
  partition: number
  /** 命中消息 offset；-1 表示该分区在目标时间之后无消息 */
  offset: number
  /** 命中消息时间戳；offset=-1 时后端省略该字段 */
  matchTimestamp?: number
}

export interface TopicOffsetLookup {
  topic: string
  timestamp: number
  partitions: PartitionOffsetLookup[]
}

// ---- Topic 配置修改 ----

export interface ConfigsUpdateResult {
  topic: string
  updated: boolean
}

// ---- 分区扩容 ----

export interface PartitionsUpdateResult {
  name: string
  partitions: number
  updated: boolean
}

// ---- 消息生产 ----

export interface ProduceResult {
  topic: string
  partition: number
  offset: number
  timestamp: number
}

// ---- 消费组（全量概览 / 重置 / 删除）----

export interface ConsumerGroupTopicProgress {
  topic: string
  partitions: PartitionOffset[]
  totalLag: number
}

export interface ConsumerGroupOverview {
  group: string
  state: string
  memberCount: number
  topics: ConsumerGroupTopicProgress[]
  totalLag: number
}

export interface ResetOffsetsResult {
  group: string
  topic: string
  reset: PartitionReset[]
  resetCount: number
}

export interface GroupDeleteResult {
  group: string
  deleted: boolean
}

// ---- API functions ----

/**
 * 路径参数安全编码。
 * Kafka topic 名本身不含 `/`、空格（后端也拒绝），但**消费组 ID 是任意字符串**：
 * 含 `/` 会把路径多切一段（404），含 `?` / `#` 会被当成 query / fragment 截断，
 * 含 `%` 会构成非法转义。统一在此收口，避免逐个函数漏改。
 */
const encodeSegment = (segment: string | number) => encodeURIComponent(String(segment))

/** 集群段路径拼装：axios baseURL '/api' 不变，功能端点统一挂在 `/c/{clusterId}` 下 */
const c = (clusterId: number, path: string) => `/c/${clusterId}${path}`

// ---- 集群注册表（全局，不带集群段）----

export const getClusters = () =>
  api.get<unknown, ApiResponse<ClusterSummary[]>>('/clusters')

/** 轻量状态轮询：只回 id + displayState + errorSummary，其余字段为占位（见 ClusterSummary 注释） */
export const getClusterStatuses = () =>
  api.get<unknown, ApiResponse<ClusterSummary[]>>('/clusters/status')

export const createCluster = (data: ClusterUpsertPayload) =>
  api.post<unknown, ApiResponse<ClusterSummary>>('/clusters', data)

export const updateCluster = (id: number, data: ClusterUpsertPayload) =>
  api.put<unknown, ApiResponse<ClusterSummary>>(`/clusters/${id}`, data)

/**
 * 保存前测试连接：不落库、不缓存，后端只建一个一次性 AdminClient 做 describeCluster。
 *
 * 失败口径（按 ApiError.code 分支，见视图层的文案映射）：
 * - `40001` 校验未通过（证书/私钥格式、必填缺失、保留键冲突等）；
 * - `50302` 连接超时（地址不可达 / 集群过慢）；
 * - `50001` 认证失败或 TLS 握手失败（凭据错误、证书不被信任等）；
 * - `40404` 携带的 clusterId 已不存在。
 */
export const validateCluster = (data: ClusterValidatePayload) =>
  api.post<unknown, ApiResponse<ClusterValidateResult>>('/clusters/validate', data)

export const deleteCluster = (id: number) =>
  api.delete<unknown, ApiResponse<ClusterSummary>>(`/clusters/${id}`)

/** 显式建连（异步）：立即返回 CONNECTING，状态交给轮询 */
export const connectCluster = (id: number) =>
  api.post<unknown, ApiResponse<ClusterSummary>>(`/clusters/${id}/connect`)

/** 断开回收（归档器一并停止；已有归档数据保留） */
export const disconnectCluster = (id: number) =>
  api.post<unknown, ApiResponse<ClusterSummary>>(`/clusters/${id}/disconnect`)

// ---- 数据源配置（全局，不带集群段）----

export const getStorageConfig = () =>
  api.get<unknown, ApiResponse<StorageInfo>>('/storage')

/** 保存前服务端也会强制 test 通过；重启后生效（响应 msg 携带提示） */
export const saveStorageConfig = (data: StorageConfigPayload) =>
  api.put<unknown, ApiResponse<StorageInfo>>('/storage', data)

export const testStorageConfig = (data: StorageConfigPayload) =>
  api.post<unknown, ApiResponse<StorageTestResult>>('/storage/test', data)

// ---- 偏好（全局，不带集群段）----

export const getPreferences = () =>
  api.get<unknown, ApiResponse<Record<string, string>>>('/preferences')

export const putPreference = (key: string, value: string) =>
  api.put<unknown, ApiResponse<Record<string, string>>>('/preferences', { key, value })

// ---- 多集群聚合仪表盘（全局，不带集群段）----

export const getMultiDashboard = () =>
  api.get<unknown, ApiResponse<MultiClusterDashboard[]>>('/dashboard/multi-overview')

// ---- 历史消息归档（离线集群可用：不触碰 Kafka 客户端）----

export const getArchiveTopics = (clusterId: number) =>
  api.get<unknown, ApiResponse<ArchiveTopicSummary[]>>(c(clusterId, '/archive/topics'))

export const queryArchiveMessages = (clusterId: number, params: ArchiveMessageQueryParams) =>
  api.get<unknown, ApiResponse<ArchiveMessageQueryResult>>(c(clusterId, '/archive/messages'), { params })

/** 清理归档：带 topic 只删该 topic 的行；不带则清空整集群归档（整表 DROP + 重建） */
export const deleteArchive = (clusterId: number, topic?: string) =>
  api.delete<unknown, ApiResponse<number>>(c(clusterId, '/archive'), {
    params: topic === undefined ? undefined : { topic },
  })

// ---- 收藏（挂集群段：收藏天然属于某集群）----

export const getFavorites = (clusterId: number) =>
  api.get<unknown, ApiResponse<FavoriteItem[]>>(c(clusterId, '/favorites'))

/** 添加收藏（幂等：重复添加不报错）；返回添加后的全量列表 */
export const addFavorite = (clusterId: number, type: 'topic' | 'group', name: string) =>
  api.post<unknown, ApiResponse<FavoriteItem[]>>(c(clusterId, '/favorites'), { type, name })

/** 移除收藏（幂等：不存在时静默成功）；query 参数而非 DELETE body（部分代理会丢 body） */
export const removeFavorite = (clusterId: number, type: 'topic' | 'group', name: string) =>
  api.delete<unknown, ApiResponse<FavoriteItem[]>>(c(clusterId, '/favorites'), { params: { type, name } })

// ---- 功能端点（全部挂集群段 /c/{clusterId}）----

export const getClusterInfo = (clusterId: number) =>
  api.get<unknown, ApiResponse<ClusterInfo>>(c(clusterId, '/cluster/info'))

export const getClusterMode = (clusterId: number) =>
  api.get<unknown, ApiResponse<ClusterMode>>(c(clusterId, '/cluster/mode'))

export const getTopics = (clusterId: number, includeInternal = false) =>
  api.get<unknown, ApiResponse<string[]>>(c(clusterId, '/topics'), { params: { includeInternal } })

export const getTopicDetail = (clusterId: number, name: string) =>
  api.get<unknown, ApiResponse<TopicDetail>>(c(clusterId, `/topics/${encodeSegment(name)}`))

export const createTopic = (clusterId: number, data: CreateTopicRequest) =>
  api.post<unknown, ApiResponse<TopicActionResult>>(c(clusterId, '/topics'), data)

export const deleteTopic = (clusterId: number, name: string) =>
  api.delete<unknown, ApiResponse<TopicActionResult>>(c(clusterId, `/topics/${encodeSegment(name)}`))

export const queryMessages = (clusterId: number, params: { topic: string; partition: number; offset: number; count: number }) =>
  api.get<unknown, ApiResponse<MessageQueryResult>>(c(clusterId, '/messages'), { params })

export const listConsumerGroups = (clusterId: number) =>
  api.get<unknown, ApiResponse<string[]>>(c(clusterId, '/consumer-groups'))

export const describeConsumerGroup = (clusterId: number, group: string, topic: string) =>
  api.get<unknown, ApiResponse<ConsumerGroupDetail>>(
    c(clusterId, `/consumer-groups/${encodeSegment(group)}/topics/${encodeSegment(topic)}`),
  )

export const getConsumerGroupOverview = (clusterId: number, group: string) =>
  api.get<unknown, ApiResponse<ConsumerGroupOverview>>(
    c(clusterId, `/consumer-groups/${encodeSegment(group)}`),
  )

export const resetOffsets = (clusterId: number, group: string, data: { topic: string; strategy: string; offset?: number; timestamp?: number; partitions?: number[] }) =>
  api.post<unknown, ApiResponse<ResetOffsetsResult>>(
    c(clusterId, `/consumer-groups/${encodeSegment(group)}/offsets/reset`),
    data,
  )

export const deleteConsumerGroup = (clusterId: number, group: string) =>
  api.delete<unknown, ApiResponse<GroupDeleteResult>>(c(clusterId, `/consumer-groups/${encodeSegment(group)}`))

export const listZkChildren = (clusterId: number, path: string, recursive = false) =>
  api.get<unknown, ApiResponse<ZkChildrenResult>>(c(clusterId, '/zk/children'), { params: { path, recursive } })

export const deleteZkNode = (clusterId: number, path: string) =>
  api.delete<unknown, ApiResponse<DeleteResult>>(c(clusterId, '/zk/node'), { params: { path } })

export const getBrokerConfigs = (clusterId: number, brokerId: number) =>
  api.get<unknown, ApiResponse<BrokerConfig>>(c(clusterId, `/cluster/metadata/broker-configs/${brokerId}`))

export const getTopicConfigs = (clusterId: number, topic: string) =>
  api.get<unknown, ApiResponse<TopicConfig>>(c(clusterId, `/cluster/metadata/topic-configs/${encodeSegment(topic)}`))

export const getAcls = (clusterId: number) =>
  api.get<unknown, ApiResponse<AclInfo[]>>(c(clusterId, '/cluster/metadata/acls'))

/**
 * 日志目录明细（含逐分区副本行）。
 * @param params.brokerId 只查该 broker —— AdminClient 无服务端过滤，这是唯一能真正
 *                        减少 broker→客户端传输量的手段
 * @param params.topic    只返回该 topic 的副本行（只缩小响应体，不减少传输量）
 */
export const getLogDirs = (clusterId: number, params?: { brokerId?: number; topic?: string }) =>
  api.get<unknown, ApiResponse<LogDirInfo[]>>(c(clusterId, '/cluster/metadata/log-dirs'), { params })

/**
 * GET /cluster/metadata/log-dirs/summary —— 日志目录汇总（每 broker 每 log dir 一行）
 * 响应体为 KB 级，适合作为 Log Dirs 页的默认视图。
 * @param params.brokerId        只统计该 broker；缺省 = 全部 broker
 * @param params.includeInternal 为 true 时把 `_` 前缀内部 topic 计入统计（缺省 false）
 */
export const getLogDirsSummary = (clusterId: number, params?: { brokerId?: number; includeInternal?: boolean }) =>
  api.get<unknown, ApiResponse<LogDirSummary[]>>(c(clusterId, '/cluster/metadata/log-dirs/summary'), { params })

/** GET /dashboard/overview —— 单集群健康概览（多集群总览请用 getMultiDashboard） */
export const getDashboardOverview = (clusterId: number) =>
  api.get<unknown, ApiResponse<DashboardOverview>>(c(clusterId, '/dashboard/overview'))

/**
 * GET /messages/offsets-for-times —— 按时间戳定位各分区首条消息 offset
 * @param params.partition 缺省时查询全部分区
 */
export const offsetsForTimes = (clusterId: number, params: { topic: string; timestamp: number; partition?: number }) =>
  api.get<unknown, ApiResponse<TopicOffsetLookup>>(c(clusterId, '/messages/offsets-for-times'), { params })

// ---- 写操作 API ----

export const expandPartitions = (clusterId: number, topic: string, partitions: number) =>
  api.post<unknown, ApiResponse<PartitionsUpdateResult>>(
    c(clusterId, `/topics/${encodeSegment(topic)}/partitions`),
    { partitions },
  )

export const produceMessage = (clusterId: number, data: { topic: string; partition?: number; key?: string; value: string; timestamp?: number; headers?: Record<string, string> }) =>
  api.post<unknown, ApiResponse<ProduceResult>>(c(clusterId, '/messages'), data)

/**
 * PATCH /cluster/metadata/topic-configs/{topic} —— 增量修改 Topic 配置
 * @param data.configs value 为 null 表示删除该覆盖项（恢复 broker 默认）
 */
export const updateTopicConfigs = (clusterId: number, topic: string, data: { configs: Record<string, string | null> }) =>
  api.patch<unknown, ApiResponse<ConfigsUpdateResult>>(
    c(clusterId, `/cluster/metadata/topic-configs/${encodeSegment(topic)}`),
    data,
  )
