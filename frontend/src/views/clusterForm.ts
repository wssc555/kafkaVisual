import type {ClusterAuthType, ClusterSummary, ClusterUpsertPayload, ClusterValidatePayload} from '../api'

/**
 * 集群表单的纯逻辑模块。
 *
 * 把"表单状态 ↔ 请求体"的映射、前端预检规则从 {@code ClusterManage.vue} 里抽出来，
 * 原因有两个：
 * 1. 多认证方式改造后映射规则明显变复杂（五种方式 × 秘密字段三态 × 派生协议/机制），
 *    塞在组件里既难读也无法单测；
 * 2. 这些规则必须与后端 `AuthSpec.deriveSecurityProtocol/deriveSaslMechanism`、
 *    `ClusterUpsertValidator` 严格对齐 —— 放在纯函数里，测试可以直接断言契约。
 *
 * 本模块不依赖 Vue、不依赖 i18n、不依赖 DOM，可在 node 环境的 vitest 里直接跑。
 * 用户可见文案以**翻译 key（+参数）**返回，视图层负责 `t()` 渲染 ——
 * 语言包在 `src/i18n/messages/clusterManage.ts`（validate / form / auth 组）。
 */

/** 结构化翻译消息：key 指向 clusterManage 域语言包；params 供命名插值。 */
export interface FormMessage {
  key: string
  params?: Record<string, string | number>
}

// ---------------------------------------------------------------
// 类型
// ---------------------------------------------------------------

/** 秘密字段名（三态语义的适用对象，与后端字段同名）。 */
export type SecretField =
  | 'password'
  | 'sslClientCertPem'
  | 'sslClientKeyPem'
  | 'sslClientKeyPassword'
  | 'sslTrustCertsPem'
  | 'oauthClientSecret'
  | 'customJaas'

/** 附加属性编辑器的一行。 */
export interface CustomPropRow {
  key: string
  value: string
}

/**
 * 表单状态。
 *
 * **秘密字段一律以"空串 = 未填写"表达**，真正的三态判定靠
 * {@link ClusterAuthForm.clearedSecrets}：
 * - 空串且不在 `clearedSecrets` 里 → 提交时**省略**（编辑时保持库中原值）；
 * - 空串且在 `clearedSecrets` 里 → 提交空串（显式清空）；
 * - 非空 → 作为新值提交。
 */
export interface ClusterAuthForm {
  name: string
  bootstrapServers: string
  authType: ClusterAuthType
  /** NONE/PASSWORD/OAUTH 有效；MTLS 恒加密（提交时强制 true） */
  tlsEnabled: boolean
  verifyHostname: boolean

  // ---- PASSWORD ----
  username: string
  password: string
  saslMechanism: string

  // ---- MTLS ----
  sslClientCertPem: string
  sslClientKeyPem: string
  sslClientKeyPassword: string
  sslTrustCertsPem: string

  // ---- OAUTH ----
  oauthTokenUrl: string
  oauthClientId: string
  oauthClientSecret: string
  oauthScope: string

  // ---- CUSTOM ----
  /** 手选协议（CUSTOM 专属） */
  customProtocol: string
  /** 手选机制（CUSTOM 专属，可空） */
  customMechanism: string
  customJaas: string
  customProps: CustomPropRow[]
  /**
   * 附加属性是否被编辑过。
   * 附加属性存的是密文、无法回显，因此"没编辑过"必须与"清空"区分开：
   * 未编辑 → 提交时省略（保持原值）；编辑过 → 提交当前内容（空则等于清空）。
   */
  customPropsDirty: boolean

  /** 被显式清空的秘密字段（见类注释的三态说明）。 */
  clearedSecrets: SecretField[]

  // ---- 其他 ----
  zkConnectString: string
  enabled: boolean
  archiveEnabled: boolean
  archiveRetentionDays: number
}

// ---------------------------------------------------------------
// 常量
// ---------------------------------------------------------------

/** PASSWORD 可选机制（与后端白名单一致）。 */
export const SASL_MECHANISMS = ['PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512'] as const

/** CUSTOM 可选协议（与后端白名单一致）。 */
export const CUSTOM_PROTOCOLS = ['PLAINTEXT', 'SSL', 'SASL_PLAINTEXT', 'SASL_SSL'] as const

/** CUSTOM 附加属性的保留键（与后端 `AuthSpec.RESERVED_CUSTOM_PROP_KEYS` 一致）。 */
export const RESERVED_CUSTOM_PROP_KEYS = [
  'bootstrap.servers',
  'security.protocol',
  'sasl.mechanism',
  'sasl.jaas.config',
]

/** 全部秘密字段名，供视图层批量渲染"清除"按钮。 */
export const SECRET_FIELDS: SecretField[] = [
  'password',
  'sslClientCertPem',
  'sslClientKeyPem',
  'sslClientKeyPassword',
  'sslTrustCertsPem',
  'oauthClientSecret',
  'customJaas',
]

/** 认证方式选项（文案以 key 形式给出，视图层经 t() 渲染，避免视图层散落文案）。 */
export const AUTH_TYPE_OPTIONS: Array<{
  value: ClusterAuthType
  labelKey: string
  hintKey: string
}> = [
  { value: 'NONE', labelKey: 'clusterManage.auth.none.label', hintKey: 'clusterManage.auth.none.hint' },
  { value: 'PASSWORD', labelKey: 'clusterManage.auth.password.label', hintKey: 'clusterManage.auth.password.hint' },
  { value: 'MTLS', labelKey: 'clusterManage.auth.mtls.label', hintKey: 'clusterManage.auth.mtls.hint' },
  { value: 'OAUTH', labelKey: 'clusterManage.auth.oauth.label', hintKey: 'clusterManage.auth.oauth.hint' },
  { value: 'CUSTOM', labelKey: 'clusterManage.auth.custom.label', hintKey: 'clusterManage.auth.custom.hint' },
]

const PEM_CERT_HEADER = '-----BEGIN CERTIFICATE-----'
const PEM_PKCS8_KEY_HEADER = '-----BEGIN PRIVATE KEY-----'
const PEM_ENCRYPTED_PKCS8_KEY_HEADER = '-----BEGIN ENCRYPTED PRIVATE KEY-----'
const PEM_PKCS1_KEY_HEADER = '-----BEGIN RSA PRIVATE KEY-----'

// ---------------------------------------------------------------
// 表单初始化与回显
// ---------------------------------------------------------------

export function defaultClusterForm(): ClusterAuthForm {
  return {
    name: '',
    bootstrapServers: '',
    authType: 'NONE',
    tlsEnabled: false,
    verifyHostname: true,
    username: '',
    password: '',
    saslMechanism: 'PLAIN',
    sslClientCertPem: '',
    sslClientKeyPem: '',
    sslClientKeyPassword: '',
    sslTrustCertsPem: '',
    oauthTokenUrl: '',
    oauthClientId: '',
    oauthClientSecret: '',
    oauthScope: '',
    customProtocol: 'SASL_SSL',
    customMechanism: '',
    customJaas: '',
    customProps: [],
    customPropsDirty: false,
    clearedSecrets: [],
    zkConnectString: '',
    enabled: true,
    archiveEnabled: true,
    archiveRetentionDays: 30,
  }
}

/**
 * 由列表项回显表单。
 *
 * 秘密材料一律留空（后端只回布尔位）—— 用户不改就不提交，改了才写新值。
 * 附加属性同样无法回显，故 `customPropsDirty` 置 false：用户不碰就不会覆盖。
 */
export function formFromSummary(cl: ClusterSummary): ClusterAuthForm {
  const authType = cl.authType ?? 'NONE'
  const form = defaultClusterForm()
  form.name = cl.name
  form.bootstrapServers = cl.bootstrapServers ?? ''
  form.authType = authType
  // MTLS 恒为 SSL：表单里以"恒开启"呈现，避免出现"选了 mTLS 却显示未加密"的错位
  form.tlsEnabled = authType === 'MTLS' ? true : cl.tlsEnabled === true
  form.verifyHostname = cl.verifyHostname !== false
  form.username = cl.username ?? ''
  form.saslMechanism = cl.saslMechanism && SASL_MECHANISMS.includes(cl.saslMechanism as never)
    ? cl.saslMechanism
    : 'PLAIN'
  form.oauthTokenUrl = cl.oauthTokenUrl ?? ''
  form.oauthClientId = cl.oauthClientId ?? ''
  form.oauthScope = cl.oauthScope ?? ''
  // CUSTOM 的协议/机制是手选值，直接取派生列的原值
  form.customProtocol = cl.securityProtocol && cl.securityProtocol !== ''
    ? cl.securityProtocol
    : 'SASL_SSL'
  form.customMechanism = cl.saslMechanism ?? ''
  form.zkConnectString = cl.zkConnectString ?? ''
  form.enabled = cl.enabled !== false
  form.archiveEnabled = cl.archiveEnabled !== false
  form.archiveRetentionDays = cl.archiveRetentionDays ?? 30
  return form
}

// ---------------------------------------------------------------
// 派生（与后端 AuthSpec 的表严格一致，供 UI 提示与测试断言）
// ---------------------------------------------------------------

/**
 * 派生 {@code security.protocol} / {@code sasl.mechanism}（镜像后端 §4.1.2）。
 *
 * 注意 NONE 且未开 TLS 时协议为空串而非 `PLAINTEXT` —— 这是后端刻意保留的
 * 历史约定（"空 = 无认证"），前端提示文案不应写成 PLAINTEXT。
 */
export function derivedProtocol(form: ClusterAuthForm): { protocol: string; mechanism: string } {
  switch (form.authType) {
    case 'NONE':
      return { protocol: form.tlsEnabled ? 'SSL' : '', mechanism: '' }
    case 'PASSWORD':
      return {
        protocol: form.tlsEnabled ? 'SASL_SSL' : 'SASL_PLAINTEXT',
        mechanism: form.saslMechanism,
      }
    case 'MTLS':
      return { protocol: 'SSL', mechanism: '' }
    case 'OAUTH':
      return {
        protocol: form.tlsEnabled ? 'SASL_SSL' : 'SASL_PLAINTEXT',
        mechanism: 'OAUTHBEARER',
      }
    case 'CUSTOM':
      return { protocol: form.customProtocol, mechanism: form.customMechanism.trim() }
  }
}

/** 派生结果的展示结构（空协议 = 无认证，视图层据此渲染 clusterManage.form.noAuthProtocol）。 */
export function derivedProtocolDisplay(form: ClusterAuthForm): {
  protocol: string
  noAuth: boolean
  mechanism: string
} {
  const { protocol, mechanism } = derivedProtocol(form)
  return { protocol, noAuth: protocol === '', mechanism }
}

// ---------------------------------------------------------------
// 秘密字段辅助
// ---------------------------------------------------------------

/** 编辑态下秘密输入框的占位文案（翻译 key，见 clusterManage.form.secret*）。 */
export function secretPlaceholder(editing: boolean, configured: boolean): string {
  if (!editing) {
    return 'clusterManage.form.secretNotConfigured'
  }
  return configured ? 'clusterManage.form.secretConfiguredKeep' : 'clusterManage.form.secretNotConfigured'
}

/** 该秘密字段是否已被标记为"显式清空"。 */
export function isCleared(form: ClusterAuthForm, field: SecretField): boolean {
  return form.clearedSecrets.includes(field)
}

/**
 * 切换秘密字段的"清除"标记。
 *
 * 语义：标记为清除时输入框被清空并**不允许再输入**（要填新值就先取消清除），
 * 这样"留空 = 不修改"与"留空 = 清空"两个状态在 UI 上不会互相打架。
 */
export function toggleCleared(form: ClusterAuthForm, field: SecretField): void {
  if (isCleared(form, field)) {
    form.clearedSecrets = form.clearedSecrets.filter((f) => f !== field)
  } else {
    form.clearedSecrets = [...form.clearedSecrets, field]
    ;(form as unknown as Record<string, string>)[field] = ''
  }
}

// ---------------------------------------------------------------
// 前端预检（后端 40001 的镜像，只做"说得出原因"的那部分）
// ---------------------------------------------------------------

/** PEM 片段的前端预检（返回翻译 key，null = 通过）。 */
export function pemHeaderError(kind: 'cert' | 'key' | 'trust', text: string): string | null {
  const value = text.trim()
  if (value === '') {
    return null
  }
  if (kind === 'key') {
    if (value.includes(PEM_ENCRYPTED_PKCS8_KEY_HEADER)) {
      return null
    }
    if (value.includes(PEM_PKCS1_KEY_HEADER)) {
      return 'clusterManage.validate.pkcs1'
    }
    if (!value.includes(PEM_PKCS8_KEY_HEADER)) {
      return 'clusterManage.validate.keyPemRequired'
    }
    return null
  }
  if (!value.includes(PEM_CERT_HEADER)) {
    return kind === 'cert'
      ? 'clusterManage.validate.certPemRequired'
      : 'clusterManage.validate.caPemRequired'
  }
  return null
}

/** 私钥是否为加密 PKCS#8（决定是否显示/必填"私钥口令"）。 */
export function isEncryptedKey(text: string): boolean {
  return text.includes(PEM_ENCRYPTED_PKCS8_KEY_HEADER)
}

/**
 * 整表校验；返回结构化消息数组（空数组 = 通过），视图层经 t() 渲染。
 *
 * @param editing true = 编辑已有集群（秘密字段可省略，沿用库中原值）
 */
export function validateAuthForm(form: ClusterAuthForm, editing: boolean): FormMessage[] {
  const errors: FormMessage[] = []

  if (!form.bootstrapServers.trim()) {
    errors.push({key: 'clusterManage.validate.bootstrapRequired'})
  }

  switch (form.authType) {
    case 'NONE':
      break

    case 'PASSWORD': {
      if (!SASL_MECHANISMS.includes(form.saslMechanism as never)) {
        errors.push({key: 'clusterManage.validate.saslMechanism', params: {list: SASL_MECHANISMS.join(' / ')}})
      }
      if (!editing && !form.password && !isCleared(form, 'password')) {
        errors.push({key: 'clusterManage.validate.passwordRequired'})
      }
      break
    }

    case 'MTLS': {
      // mTLS 的证书/私钥不支持"清除":没有客户端证书就不是 mTLS(后端也会 40001 拒绝)。
      // 前端提前拦住,避免用户白等一次请求才看到错误。
      // 注:UI 已移除证书/私钥的清除入口,本分支保留作"绕过 UI 直接提交
      // clearedSecrets"时的兜底防御;若确认不会发生可整体删除。
      if (isCleared(form, 'sslClientCertPem') || isCleared(form, 'sslClientKeyPem')) {
        errors.push({key: 'clusterManage.validate.mtlsNoClear'})
      }
      const cert = form.sslClientCertPem.trim()
      const key = form.sslClientKeyPem.trim()
      const keyConfigured = isCleared(form, 'sslClientKeyPem') ? '' : key
      if (!editing && cert === '' && !isCleared(form, 'sslClientCertPem')) {
        errors.push({key: 'clusterManage.validate.certRequired'})
      }
      if (!editing && keyConfigured === '') {
        errors.push({key: 'clusterManage.validate.keyRequired'})
      }
      const certError = pemHeaderError('cert', cert)
      if (certError && cert !== '') {
        errors.push({key: certError})
      }
      const keyError = pemHeaderError('key', key)
      if (keyError && key !== '') {
        errors.push({key: keyError})
      }
      if (isEncryptedKey(key) && !form.sslClientKeyPassword && !editing) {
        errors.push({key: 'clusterManage.validate.keyPasswordRequired'})
      }
      const trustError = pemHeaderError('trust', form.sslTrustCertsPem)
      if (trustError) {
        errors.push({key: trustError})
      }
      break
    }

    case 'OAUTH': {
      const url = form.oauthTokenUrl.trim()
      if (!editing && url === '') {
        errors.push({key: 'clusterManage.validate.tokenUrlRequired'})
      }
      if (url !== '' && !url.startsWith('http://') && !url.startsWith('https://')) {
        errors.push({key: 'clusterManage.validate.tokenUrlHttp'})
      }
      if (!editing && form.oauthClientId.trim() === '') {
        errors.push({key: 'clusterManage.validate.clientIdRequired'})
      }
      if (!editing && !form.oauthClientSecret && !isCleared(form, 'oauthClientSecret')) {
        errors.push({key: 'clusterManage.validate.clientSecretRequired'})
      }
      break
    }

    case 'CUSTOM': {
      if (!CUSTOM_PROTOCOLS.includes(form.customProtocol as never)) {
        errors.push({key: 'clusterManage.validate.protocolInvalid', params: {list: CUSTOM_PROTOCOLS.join(' / ')}})
      }
      const { error } = collectCustomProps(form.customProps)
      if (error) {
        errors.push(error)
      }
      break
    }
  }

  return errors
}

/**
 * 附加属性行 → 对象。
 *
 * 空行（键值都空）直接跳过；其余非法情形给出可读文案。
 */
export function collectCustomProps(rows: CustomPropRow[]): { props: Record<string, string>; error?: FormMessage } {
  const props: Record<string, string> = {}
  for (const row of rows) {
    const key = row.key.trim()
    if (key === '' && row.value.trim() === '') {
      continue
    }
    if (key === '') {
      return { props, error: {key: 'clusterManage.validate.propEmptyKey'} }
    }
    if (RESERVED_CUSTOM_PROP_KEYS.includes(key.toLowerCase())) {
      return {
        props,
        error: {
          key: 'clusterManage.validate.propReservedKey',
          params: {key, reserved: RESERVED_CUSTOM_PROP_KEYS.join(' / ')},
        },
      }
    }
    if (Object.prototype.hasOwnProperty.call(props, key)) {
      return { props, error: {key: 'clusterManage.validate.propDuplicateKey', params: {key}} }
    }
    props[key] = row.value
  }
  return { props }
}

// ---------------------------------------------------------------
// 请求体构建
// ---------------------------------------------------------------

export interface BuildPayloadOptions {
  /** true = 编辑已有集群：留空且未标记清除的秘密字段一律省略（保持库中原值） */
  editing: boolean
}

/**
 * 表单 → upsert / validate 请求体。
 *
 * 三条不变量（后端契约）：
 * 1. **只提交当前认证方式用得到的字段** —— 切换认证方式不该顺手清掉另一种方式的凭据，
 *    用户切回去还能用；
 * 2. **秘密字段三态**：非空 → 新值；空且标记清除 → 空串；空且未标记 → 省略；
 * 3. `tlsEnabled` 对 MTLS 强制 true（语义上恒加密；后端对 MTLS 忽略该值）。
 */
export function buildClusterPayload(form: ClusterAuthForm, opts: BuildPayloadOptions): ClusterUpsertPayload {
  const payload: ClusterUpsertPayload = {
    name: form.name.trim(),
    bootstrapServers: form.bootstrapServers.trim(),
    authType: form.authType,
    verifyHostname: form.verifyHostname,
    zkConnectString: form.zkConnectString.trim(),
    enabled: form.enabled,
    archiveEnabled: form.archiveEnabled,
    archiveRetentionDays: form.archiveRetentionDays,
  }

  if (form.authType !== 'MTLS') {
    payload.tlsEnabled = form.tlsEnabled
  }

  switch (form.authType) {
    case 'NONE':
      break

    case 'PASSWORD':
      payload.saslMechanism = form.saslMechanism
      payload.username = form.username.trim()
      applySecret(payload, form, 'password')
      break

    case 'MTLS':
      payload.tlsEnabled = true
      applySecret(payload, form, 'sslClientCertPem')
      applySecret(payload, form, 'sslClientKeyPem')
      applySecret(payload, form, 'sslClientKeyPassword')
      applySecret(payload, form, 'sslTrustCertsPem')
      break

    case 'OAUTH':
      payload.oauthTokenUrl = form.oauthTokenUrl.trim()
      payload.oauthClientId = form.oauthClientId.trim()
      payload.oauthScope = form.oauthScope.trim()
      applySecret(payload, form, 'oauthClientSecret')
      break

    case 'CUSTOM': {
      payload.securityProtocol = form.customProtocol
      payload.saslMechanism = form.customMechanism.trim()
      applySecret(payload, form, 'customJaas')
      const { props } = collectCustomProps(form.customProps)
      const hasProps = Object.keys(props).length > 0
      if (hasProps) {
        payload.customProps = props
      } else if (!opts.editing || form.customPropsDirty) {
        // 编辑态且未编辑过 → 省略（保持库中原值）；否则显式清空
        payload.customProps = {}
      }
      break
    }
  }

  return payload
}

/** 测连请求体 = upsert 请求体 + 可选 clusterId（后端据此补全省略的凭据）。 */
export function buildValidatePayload(
  form: ClusterAuthForm,
  opts: BuildPayloadOptions,
  clusterId?: number | null,
): ClusterValidatePayload {
  const payload: ClusterValidatePayload = buildClusterPayload(form, opts)
  if (clusterId != null) {
    payload.clusterId = clusterId
  }
  return payload
}

/** 秘密字段三态的落地点（见 {@link buildClusterPayload} 的不变量 2）。 */
function applySecret<K extends SecretField>(
  payload: ClusterUpsertPayload,
  form: ClusterAuthForm,
  field: K,
): void {
  const value = (form[field] as string) ?? ''
  if (value !== '') {
    // ClusterUpsertPayload 无索引签名,动态键写入须先经 unknown
    ;(payload as unknown as Record<string, unknown>)[field] = value
    return
  }
  if (isCleared(form, field)) {
    ;(payload as unknown as Record<string, unknown>)[field] = ''
  }
}
