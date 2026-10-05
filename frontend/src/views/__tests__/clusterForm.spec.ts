import {describe, expect, it} from 'vitest'
import type {ClusterSummary} from '../../api'
import {
    buildClusterPayload,
    buildValidatePayload,
    type ClusterAuthForm,
    collectCustomProps,
    defaultClusterForm,
    derivedProtocol,
    derivedProtocolDisplay,
    formFromSummary,
    isCleared,
    isEncryptedKey,
    pemHeaderError,
    secretPlaceholder,
    toggleCleared,
    validateAuthForm,
} from '../clusterForm'

/**
 * clusterForm 纯函数契约测试（断言翻译 key 而非中文文案）。
 *
 * 这些断言直接对应后端契约，改动 `clusterForm.ts` 时它们是第一道防线：
 * - 派生规则必须与后端 `AuthSpec.deriveSecurityProtocol/deriveSaslMechanism` 一致；
 * - 秘密字段三态必须与 `ClusterConfigStore.resolveSecret` 一致；
 * - 预检规则必须与 `ClusterUpsertValidator` 一致。
 * key → 文案的映射在 `src/i18n/messages/clusterManage.ts`，由视图层 t() 渲染。
 */

const CERT = '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----'
const PKCS8_KEY = '-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----'
const ENCRYPTED_KEY = '-----BEGIN ENCRYPTED PRIVATE KEY-----\nMIIE\n-----END ENCRYPTED PRIVATE KEY-----'
const PKCS1_KEY = '-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----'

/** 造一个以默认表单为基础、按需覆盖字段的表单。 */
const form = (patch: Partial<ClusterAuthForm> = {}): ClusterAuthForm => ({
  ...defaultClusterForm(),
  bootstrapServers: 'broker:9092',
  ...patch,
})

describe('派生规则（镜像后端 §4.1.2）', () => {
  it('NONE 不加密时协议为空串而非 PLAINTEXT（后端刻意保留的历史约定）', () => {
    expect(derivedProtocol(form({authType: 'NONE', tlsEnabled: false}))).toEqual({
      protocol: '',
      mechanism: '',
    })
    expect(derivedProtocolDisplay(form({authType: 'NONE', tlsEnabled: false}))).toEqual({
      protocol: '',
      noAuth: true,
      mechanism: '',
    })
  })

  it('NONE 开启 TLS → SSL，且提示文案不再写"无认证"', () => {
    expect(derivedProtocol(form({authType: 'NONE', tlsEnabled: true}))).toEqual({
      protocol: 'SSL',
      mechanism: '',
    })
    expect(derivedProtocolDisplay(form({authType: 'NONE', tlsEnabled: true}))).toEqual({
      protocol: 'SSL',
      noAuth: false,
      mechanism: '',
    })
  })

  it('PASSWORD 按 tlsEnabled 在 SASL_SSL / SASL_PLAINTEXT 之间切换，机制取自表单', () => {
    expect(
      derivedProtocol(form({authType: 'PASSWORD', tlsEnabled: true, saslMechanism: 'SCRAM-SHA-512'})),
    ).toEqual({protocol: 'SASL_SSL', mechanism: 'SCRAM-SHA-512'})
    expect(derivedProtocol(form({authType: 'PASSWORD', tlsEnabled: false}))).toEqual({
      protocol: 'SASL_PLAINTEXT',
      mechanism: 'PLAIN',
    })
  })

  it('MTLS 恒为 SSL，与 tlsEnabled 无关', () => {
    expect(derivedProtocol(form({authType: 'MTLS', tlsEnabled: false}))).toEqual({
      protocol: 'SSL',
      mechanism: '',
    })
  })

  it('OAUTH 机制恒为 OAUTHBEARER', () => {
    expect(derivedProtocol(form({authType: 'OAUTH', tlsEnabled: true}))).toEqual({
      protocol: 'SASL_SSL',
      mechanism: 'OAUTHBEARER',
    })
  })

  it('CUSTOM 用表单里手选的协议与机制', () => {
    expect(
      derivedProtocol(form({authType: 'CUSTOM', customProtocol: 'SASL_PLAINTEXT', customMechanism: 'GSSAPI'})),
    ).toEqual({protocol: 'SASL_PLAINTEXT', mechanism: 'GSSAPI'})
  })
})

describe('请求体构建：秘密字段三态', () => {
  it('新建时填了密码 → 提交新值', () => {
    const payload = buildClusterPayload(
      form({authType: 'PASSWORD', password: 's3cret'}),
      {editing: false},
    )
    expect(payload.password).toBe('s3cret')
    expect(payload.authType).toBe('PASSWORD')
    expect(payload.securityProtocol).toBeUndefined() // 非 CUSTOM 不提交手选协议
  })

  it('编辑时留空 → 省略字段（后端保持原口令）', () => {
    const payload = buildClusterPayload(
      form({authType: 'PASSWORD', password: ''}),
      {editing: true},
    )
    expect('password' in payload).toBe(false)
  })

  it('编辑时点了"清除" → 提交空串（后端显式清空）', () => {
    const f = form({authType: 'PASSWORD'})
    toggleCleared(f, 'password')
    expect(isCleared(f, 'password')).toBe(true)
    const payload = buildClusterPayload(f, {editing: true})
    expect(payload.password).toBe('')
  })

  it('清除标记与输入互斥：标记清除会清空输入框，取消清除后恢复可填', () => {
    const f = form({authType: 'PASSWORD', password: 'typed'})
    toggleCleared(f, 'password')
    expect(f.password).toBe('')
    toggleCleared(f, 'password')
    expect(isCleared(f, 'password')).toBe(false)
  })

  it('只提交当前认证方式的字段：PASSWORD 不带证书/OAuth 字段', () => {
    const payload = buildClusterPayload(form({authType: 'PASSWORD', password: 'p'}), {editing: false})
    expect('sslClientCertPem' in payload).toBe(false)
    expect('sslClientKeyPem' in payload).toBe(false)
    expect('oauthClientSecret' in payload).toBe(false)
    expect('customJaas' in payload).toBe(false)
  })

  it('MTLS 强制 tlsEnabled=true（语义上恒加密）', () => {
    const payload = buildClusterPayload(
      form({
        authType: 'MTLS',
        tlsEnabled: false,
        sslClientCertPem: CERT,
        sslClientKeyPem: PKCS8_KEY,
      }),
      {editing: false},
    )
    expect(payload.tlsEnabled).toBe(true)
    expect(payload.sslClientCertPem).toBe(CERT)
    expect(payload.sslClientKeyPem).toBe(PKCS8_KEY)
  })

  it('NONE/PASSWORD 透传 tlsEnabled 与 verifyHostname', () => {
    const payload = buildClusterPayload(
      form({authType: 'PASSWORD', tlsEnabled: false, verifyHostname: false, password: 'p'}),
      {editing: false},
    )
    expect(payload.tlsEnabled).toBe(false)
    expect(payload.verifyHostname).toBe(false)
  })
})

describe('请求体构建：CUSTOM 附加属性', () => {
  it('有内容时提交对象', () => {
    const payload = buildClusterPayload(
      form({
        authType: 'CUSTOM',
        customProtocol: 'SASL_SSL',
        customMechanism: 'AWS_MSK_IAM',
        customProps: [{key: 'sasl.login.class', value: 'com.example.Login'}],
      }),
      {editing: false},
    )
    expect(payload.securityProtocol).toBe('SASL_SSL')
    expect(payload.saslMechanism).toBe('AWS_MSK_IAM')
    expect(payload.customProps).toEqual({'sasl.login.class': 'com.example.Login'})
  })

  it('编辑时未编辑过附加属性 → 省略（保持库中原值）', () => {
    const payload = buildClusterPayload(
      form({authType: 'CUSTOM', customProps: [], customPropsDirty: false}),
      {editing: true},
    )
    expect('customProps' in payload).toBe(false)
  })

  it('编辑时清空了附加属性 → 提交空对象（显式清空）', () => {
    const payload = buildClusterPayload(
      form({authType: 'CUSTOM', customProps: [], customPropsDirty: true}),
      {editing: true},
    )
    expect(payload.customProps).toEqual({})
  })

  it('collectCustomProps 跳过全空行，并拒绝保留键 / 重复键 / 空键', () => {
    expect(collectCustomProps([{key: '', value: ''}, {key: 'a', value: '1'}])).toEqual({
      props: {a: '1'},
    })
    expect(collectCustomProps([{key: 'security.protocol', value: 'SSL'}]).error?.key).toBe(
      'clusterManage.validate.propReservedKey',
    )
    expect(collectCustomProps([{key: 'a', value: '1'}, {key: 'a', value: '2'}]).error?.key).toBe(
      'clusterManage.validate.propDuplicateKey',
    )
    expect(collectCustomProps([{key: '', value: 'x'}]).error?.key).toBe(
      'clusterManage.validate.propEmptyKey',
    )
  })
})

describe('前端预检（后端 ClusterUpsertValidator 的镜像，断言翻译 key）', () => {
  it('新建 PASSWORD 缺密码 → 报错；编辑态可省略', () => {
    expect(validateAuthForm(form({authType: 'PASSWORD'}), false)).toContainEqual({
      key: 'clusterManage.validate.passwordRequired',
    })
    expect(validateAuthForm(form({authType: 'PASSWORD'}), true)).toEqual([])
  })

  it('PASSWORD 机制必须在白名单内', () => {
    const errors = validateAuthForm(form({authType: 'PASSWORD', saslMechanism: 'GSSAPI', password: 'p'}), false)
    expect(errors.some((e) => e.key === 'clusterManage.validate.saslMechanism')).toBe(true)
  })

  it('MTLS 新建必须给证书与私钥', () => {
    const errors = validateAuthForm(form({authType: 'MTLS'}), false)
    expect(errors).toContainEqual({key: 'clusterManage.validate.certRequired'})
    expect(errors).toContainEqual({key: 'clusterManage.validate.keyRequired'})
  })

  it('MTLS 证书/私钥不允许"清除"（没有证书就不是 mTLS，后端同样 40001）', () => {
    const f = form({authType: 'MTLS', sslClientCertPem: CERT, sslClientKeyPem: PKCS8_KEY})
    toggleCleared(f, 'sslClientCertPem')
    const errors = validateAuthForm(f, true)
    expect(errors).toContainEqual({key: 'clusterManage.validate.mtlsNoClear'})
  })

  it('MTLS 私钥为 PKCS#1 → 给出 openssl 转换提示', () => {
    const errors = validateAuthForm(
      form({authType: 'MTLS', sslClientCertPem: CERT, sslClientKeyPem: PKCS1_KEY}),
      false,
    )
    expect(errors.some((e) => e.key === 'clusterManage.validate.pkcs1')).toBe(true)
  })

  it('MTLS 加密私钥缺口令 → 报错；给了口令则通过', () => {
    const withoutPassword = validateAuthForm(
      form({authType: 'MTLS', sslClientCertPem: CERT, sslClientKeyPem: ENCRYPTED_KEY}),
      false,
    )
    expect(withoutPassword).toContainEqual({key: 'clusterManage.validate.keyPasswordRequired'})

    const withPassword = validateAuthForm(
      form({
        authType: 'MTLS',
        sslClientCertPem: CERT,
        sslClientKeyPem: ENCRYPTED_KEY,
        sslClientKeyPassword: 'keypass',
      }),
      false,
    )
    expect(withPassword).toEqual([])
  })

  it('OAUTH 新建必须给端点 / client id / secret，且端点必须是 http(s)', () => {
    expect(validateAuthForm(form({authType: 'OAUTH'}), false)).toEqual(
      expect.arrayContaining([
        {key: 'clusterManage.validate.tokenUrlRequired'},
        {key: 'clusterManage.validate.clientIdRequired'},
      ]),
    )
    const badUrl = validateAuthForm(
      form({authType: 'OAUTH', oauthTokenUrl: 'idp.example.com/token', oauthClientId: 'id', oauthClientSecret: 's'}),
      false,
    )
    expect(badUrl).toContainEqual({key: 'clusterManage.validate.tokenUrlHttp'})
  })

  it('CUSTOM 协议必须在四值白名单内，且附加属性受同一套校验', () => {
    expect(
      validateAuthForm(form({authType: 'CUSTOM', customProtocol: 'SASL_PLAIN'}), false).some(
        (e) => e.key === 'clusterManage.validate.protocolInvalid',
      ),
    ).toBe(true)
    expect(
      validateAuthForm(
        form({authType: 'CUSTOM', customProtocol: 'SASL_SSL', customProps: [{key: 'sasl.mechanism', value: 'X'}]}),
        false,
      ).some((e) => e.key === 'clusterManage.validate.propReservedKey'),
    ).toBe(true)
  })

  it('bootstrapServers 为空一律报错（包括测连场景）', () => {
    expect(validateAuthForm(form({bootstrapServers: '  '}), true)).toContainEqual({
      key: 'clusterManage.validate.bootstrapRequired',
    })
  })

  it('pemHeaderError 只对"填了但不合法"的情况报错，空值交给必填规则（返回翻译 key）', () => {
    expect(pemHeaderError('cert', '')).toBeNull()
    expect(pemHeaderError('cert', 'not-a-pem')).toBe('clusterManage.validate.certPemRequired')
    expect(pemHeaderError('key', PKCS8_KEY)).toBeNull()
    expect(pemHeaderError('key', ENCRYPTED_KEY)).toBeNull()
    expect(pemHeaderError('trust', CERT)).toBeNull()
    expect(isEncryptedKey(ENCRYPTED_KEY)).toBe(true)
    expect(isEncryptedKey(PKCS8_KEY)).toBe(false)
  })
})

describe('表单回显与测连请求体', () => {
  const summary = (patch: Partial<ClusterSummary> = {}): ClusterSummary => ({
    id: 7,
    name: 'prod',
    bootstrapServers: 'a:9092,b:9092',
    displayState: 'OFFLINE',
    ...patch,
  })

  it('回显时秘密字段一律留空（后端只回布尔位）', () => {
    const f = formFromSummary(
      summary({
        authType: 'MTLS',
        credentialPresence: {
          password: false,
          clientCert: true,
          clientKey: true,
          clientKeyPassword: false,
          trustCerts: false,
          oauthClientSecret: false,
          customJaas: false,
          customProps: false,
        },
      }),
    )
    expect(f.sslClientCertPem).toBe('')
    expect(f.sslClientKeyPem).toBe('')
    expect(f.clearedSecrets).toEqual([])
    // MTLS 恒加密:表单里显示为已开启
    expect(f.tlsEnabled).toBe(true)
    expect(secretPlaceholder(true, true)).toBe('clusterManage.form.secretConfiguredKeep')
    expect(secretPlaceholder(true, false)).toBe('clusterManage.form.secretNotConfigured')
    expect(secretPlaceholder(false, true)).toBe('clusterManage.form.secretNotConfigured')
  })

  it('CUSTOM 回显取派生列作为手选值；未识别的机制回落 PLAIN', () => {
    const custom = formFromSummary(
      summary({authType: 'CUSTOM', securityProtocol: 'SASL_SSL', saslMechanism: 'GSSAPI'}),
    )
    expect(custom.customProtocol).toBe('SASL_SSL')
    expect(custom.customMechanism).toBe('GSSAPI')

    const password = formFromSummary(summary({authType: 'PASSWORD', saslMechanism: 'OAUTHBEARER'}))
    expect(password.saslMechanism).toBe('PLAIN')
  })

  it('authType 缺失（旧后端 / status 轻量摘要）回落 NONE', () => {
    expect(formFromSummary(summary()).authType).toBe('NONE')
  })

  it('buildValidatePayload 带上 clusterId，便于后端补全省略的凭据', () => {
    const payload = buildValidatePayload(form({authType: 'PASSWORD'}), {editing: true}, 7)
    expect(payload.clusterId).toBe(7)
    expect('password' in payload).toBe(false)
  })

  it('buildValidatePayload 新建场景不带 clusterId', () => {
    const payload = buildValidatePayload(form({authType: 'NONE'}), {editing: false}, null)
    expect('clusterId' in payload).toBe(false)
  })
})
