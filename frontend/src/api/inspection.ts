/**
 * 定时巡检与告警 API。
 *
 * 与 `api/index.ts` 共用同一个 axios 实例（见那里的 `export {api}`）——
 * 桌面模式下的 baseURL 握手只改那一个实例，另起实例会让全部巡检端点打到 '/api'。
 *
 * 路径分工（与后端一致）：
 * - 集群段 `/api/c/{clusterId}/inspection/**`：规则 CRUD、指标目录、试运行；
 * - 全局段 `/api/inspection/**`：通道、告警中心、概览。
 * 响应信封不变 `{code,msg,data}`，axios 拦截器无需改动。
 */
import {api} from './index'

/** 后端 @JsonInclude(NON_NULL) 会省略 null 字段，data 内部可选字段一律 `?` */
export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

// ---- 类型 ----

export type MetricScopeValue = 'CLUSTER' | 'TOPIC' | 'CONSUMER_GROUP' | 'ZK'
export type SeverityValue = 'INFO' | 'WARN' | 'CRITICAL'
export type AlertStateValue = 'FIRING' | 'RECOVERED'
export type ChannelTypeValue = 'INSITE' | 'EMAIL' | 'WEBHOOK' | 'IM_BOT'

export interface MetricCatalogItem {
  key: string
  scope: MetricScopeValue
  valueType: 'NUMBER' | 'STRING' | 'BOOL'
  unit?: string | null
  description?: string | null
}

export interface RuleWindowSpec {
  count: number
  match: 'ALL' | 'ANY'
}

export interface RuleConditionSpec {
  metric: string
  operator: string
  threshold: string
  window?: RuleWindowSpec | null
}

export interface RuleListItem {
  id: number
  clusterId: number
  name: string
  enabled: boolean
  severity: SeverityValue
  metricScope: MetricScopeValue
  scopeFilter?: string | null
  cronExpr: string
  description?: string | null
  conditionSummary?: string | null
  channelNames?: string[] | null
  lastState?: string | null
  lastEvaluatedAt?: string | null
  nextFireAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface RulePage {
  total: number
  page: number
  size: number
  items: RuleListItem[]
}

export interface InspectionRule {
  id: number
  clusterId: number
  name: string
  enabled: boolean
  severity: SeverityValue
  metricScope: MetricScopeValue
  scopeFilter?: string | null
  conditionJson?: string | null
  cronExpr: string
  nextFireAt?: string | null
  description?: string | null
  channelIds?: number[] | null
  channelNames?: string[] | null
  conditionSummary?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface CreateRulePayload {
  name: string
  severity: SeverityValue
  metricScope: MetricScopeValue
  scopeFilter?: string | null
  combinator?: 'AND' | 'OR'
  conditions: RuleConditionSpec[]
  cronExpr: string
  channelIds: number[]
  description?: string | null
  enabled?: boolean
}

export interface UpdateRulePayload {
  name?: string
  severity?: SeverityValue
  metricScope?: MetricScopeValue
  scopeFilter?: string | null
  combinator?: 'AND' | 'OR'
  conditions?: RuleConditionSpec[]
  cronExpr?: string
  channelIds?: number[]
  description?: string | null
  enabled?: boolean
}

export interface RuleTestResult {
  fired: boolean
  insufficientSamples: boolean
  firedScopes?: string[] | null
  metricValues?: Record<string, Record<string, string>> | null
}

export interface AlertChannel {
  id: number
  name: string
  type: ChannelTypeValue
  enabled: boolean
  /** 敏感字段已由后端替换为 '******' */
  config?: Record<string, unknown> | null
}

export interface CreateChannelPayload {
  name: string
  type: ChannelTypeValue
  config: Record<string, unknown>
  enabled?: boolean
}

export interface UpdateChannelPayload {
  name?: string
  type?: ChannelTypeValue
  /** 部分配置：省略=保持，''=清空，'******'=保持密文 */
  config?: Record<string, unknown>
  enabled?: boolean
}

export interface ChannelTestResult {
  success: boolean
  errorMessage?: string | null
}

export interface AlertRecordItem {
  id: number
  ruleId: number
  clusterId: number
  clusterName?: string | null
  scopeId?: string | null
  severity: SeverityValue
  state: AlertStateValue
  title: string
  message?: string | null
  firedAt?: string | null
  recoveredAt?: string | null
  acknowledgedAt?: string | null
  acknowledgedBy?: string | null
}

export interface AlertDispatchLogItem {
  id: number
  alertId: number
  channelId: number
  channelName?: string | null
  status: 'SUCCESS' | 'FAILED'
  errorMessage?: string | null
  createdAt?: string | null
}

export interface AlertDetail {
  alert: AlertRecordItem
  dispatchLogs?: AlertDispatchLogItem[] | null
}

export interface InspectionOverview {
  totalRules: number
  enabledRules: number
  firingCount: number
  unreadCount: number
  recoveredToday: number
  bySeverity?: Record<string, number> | null
  recentAlerts?: AlertRecordItem[] | null
}

// ---- 路径 ----

const c = (clusterId: number, path: string) => `/c/${clusterId}/inspection${path}`

// ---- 指标目录（集群段）----

/** GET /api/c/{id}/inspection/metrics —— KRaft 集群不含 ZK 级指标 */
export const getMetricCatalog = (clusterId: number) =>
  api.get<unknown, ApiResponse<MetricCatalogItem[]>>(c(clusterId, '/metrics'))

// ---- 规则（集群段）----

export const getRules = (
  clusterId: number,
  params?: { enabled?: boolean; scope?: string; name?: string; page?: number; size?: number },
) => api.get<unknown, ApiResponse<RulePage>>(c(clusterId, '/rules'), {params})

export const getRule = (clusterId: number, id: number) =>
  api.get<unknown, ApiResponse<InspectionRule>>(c(clusterId, `/rules/${id}`))

export const createRule = (clusterId: number, data: CreateRulePayload) =>
  api.post<unknown, ApiResponse<InspectionRule>>(c(clusterId, '/rules'), data)

export const updateRule = (clusterId: number, id: number, data: UpdateRulePayload) =>
  api.put<unknown, ApiResponse<InspectionRule>>(c(clusterId, `/rules/${id}`), data)

export const deleteRule = (clusterId: number, id: number) =>
  api.delete<unknown, ApiResponse<null>>(c(clusterId, `/rules/${id}`))

export const toggleRule = (clusterId: number, id: number, enabled: boolean) =>
  api.post<unknown, ApiResponse<InspectionRule>>(c(clusterId, `/rules/${id}/toggle`), {enabled})

/** POST .../rules/{id}/test —— 试运行：不发告警、不写状态机 */
export const testRule = (clusterId: number, id: number) =>
  api.post<unknown, ApiResponse<RuleTestResult>>(c(clusterId, `/rules/${id}/test`))

// ---- 通道（全局）----

export const getChannels = () => api.get<unknown, ApiResponse<AlertChannel[]>>('/inspection/channels')

export const getChannel = (id: number) =>
  api.get<unknown, ApiResponse<AlertChannel>>(`/inspection/channels/${id}`)

export const createChannel = (data: CreateChannelPayload) =>
  api.post<unknown, ApiResponse<AlertChannel>>('/inspection/channels', data)

export const updateChannel = (id: number, data: UpdateChannelPayload) =>
  api.put<unknown, ApiResponse<AlertChannel>>(`/inspection/channels/${id}`, data)

export const deleteChannel = (id: number) =>
  api.delete<unknown, ApiResponse<null>>(`/inspection/channels/${id}`)

export const testChannel = (id: number) =>
  api.post<unknown, ApiResponse<ChannelTestResult>>(`/inspection/channels/${id}/test`)

// ---- 告警中心（全局）----

export const getAlerts = (params?: {
  state?: string
  severity?: string
  ruleId?: number
  clusterId?: number
  acknowledged?: boolean
  firedFrom?: string
  firedTo?: string
  page?: number
  size?: number
}) => api.get<unknown, ApiResponse<AlertRecordItem[]>>('/inspection/alerts', {params})

export const countAlerts = (params?: {
  state?: string
  severity?: string
  ruleId?: number
  clusterId?: number
  acknowledged?: boolean
  firedFrom?: string
  firedTo?: string
}) => api.get<unknown, ApiResponse<number>>('/inspection/alerts/count', {params})

export const getUnreadCount = () =>
  api.get<unknown, ApiResponse<{count: number}>>('/inspection/alerts/unread-count')

export const getAlert = (id: number) =>
  api.get<unknown, ApiResponse<AlertDetail>>(`/inspection/alerts/${id}`)

/** POST /alerts/{id}/ack —— 已确认再确认返回 40904 */
export const ackAlert = (id: number, by?: string) =>
  api.post<unknown, ApiResponse<AlertRecordItem>>(`/inspection/alerts/${id}/ack`, by ? {by} : {})

/** GET /inspection/overview —— 跨集群聚合概览 */
export const getInspectionOverview = () =>
  api.get<unknown, ApiResponse<InspectionOverview>>('/inspection/overview')
