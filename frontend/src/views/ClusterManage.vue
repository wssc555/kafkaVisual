<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span style="font-weight: 600">{{ t('clusterManage.page.title') }}</span>
          <div class="header-actions">
            <el-button :loading="loading" size="small" @click="reload">{{ t('common.refresh') }}</el-button>
            <el-button size="small" type="primary" @click="openCreate">{{ t('clusterManage.form.createTitle') }}</el-button>
          </div>
        </div>
      </template>

      <!-- 零集群引导:所有功能页已被重定向到此,给出明确的第一步 -->
      <el-empty v-if="clustersLoaded && clusters.length === 0" :description="t('clusterManage.page.emptyHint')">
        <el-button type="primary" @click="openCreate">{{ t('clusterManage.page.emptyAction') }}</el-button>
      </el-empty>

      <el-table v-else v-loading="loading" :data="clusters" size="small" stripe>
        <el-table-column :label="t('clusterManage.page.colName')" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="name-cell">
              <el-tooltip :disabled="!row.errorSummary" placement="top">
                <template #content>
                  <div style="max-width: 320px; white-space: pre-wrap">{{ errorTooltip(row) }}</div>
                </template>
                <span :style="{ background: dotColor(row) }" class="state-dot" />
              </el-tooltip>
              {{ row.name }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Bootstrap Servers" min-width="200" prop="bootstrapServers" show-overflow-tooltip />
        <!-- 认证方式:判别符 + 传输加密标记 -->
        <el-table-column :label="t('clusterManage.page.colAuth')" width="130">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ authLabel(row.authType) }}</el-tag>
            <el-tag
              v-if="row.authType !== 'MTLS' && row.tlsEnabled"
              effect="plain"
              size="small"
              style="margin-left: 4px"
              type="info"
            >
              TLS
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('clusterManage.page.colStatus')" width="100">
          <template #default="{ row }">
            <el-tag :type="stateTagType(row.displayState)" size="small">
              {{ stateText(row.displayState) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('clusterManage.page.colZk')" align="center" width="70">
          <template #default="{ row }">
            <el-tag :type="row.zkAvailable ? 'info' : 'info'" effect="plain" size="small">
              {{ row.zkAvailable ? t('clusterManage.page.zkYes') : 'KRaft' }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- 窄档次要列收起:归档开关与保留天数在窄表下不重要 -->
        <el-table-column v-if="!isNarrow" :label="t('clusterManage.page.colArchive')" align="center" width="80">
          <template #default="{ row }">
            <el-tag :type="row.archiveEnabled ? 'success' : 'info'" effect="plain" size="small">
              {{ row.archiveEnabled ? t('clusterManage.page.archiveOn') : t('clusterManage.page.archiveOff') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="!isNarrow" :label="t('clusterManage.page.colRetention')" align="center" width="80">
          <template #default="{ row }">{{ row.archiveEnabled ? t('clusterManage.page.days', {n: row.archiveRetentionDays}) : '-' }}</template>
        </el-table-column>
        <el-table-column :label="t('common.operation')" align="center" fixed="right" width="230">
          <template #default="{ row }">
            <el-button
              v-if="row.displayState !== 'ONLINE'"
              :loading="row.displayState === 'CONNECTING'"
              link
              size="small"
              type="primary"
              @click="handleConnect(row)"
            >
              {{ t('clusterManage.page.connect') }}
            </el-button>
            <el-button
              v-else
              link
              size="small"
              type="warning"
              @click="handleDisconnect(row)"
            >
              {{ t('clusterManage.page.disconnect') }}
            </el-button>
            <el-button link size="small" type="primary" @click="openEdit(row)">{{ t('common.edit') }}</el-button>
            <el-button link size="small" type="danger" @click="handleDelete(row)">{{ t('common.remove') }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <p class="page-hint">
        {{ t('clusterManage.page.pageHint') }}
      </p>
    </el-card>

    <!-- 新建 / 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? t('clusterManage.form.createTitle') : t('clusterManage.form.editTitle')"
      destroy-on-close
      width="min(760px, 94%)"
    >
      <el-form ref="formRef" :label-position="isNarrow ? 'top' : 'right'" :model="form" :rules="rules" label-width="150px">
        <el-form-item :label="t('clusterManage.form.nameLabel')" prop="name">
          <el-input v-model="form.name" :placeholder="t('clusterManage.form.namePlaceholder')" maxlength="64" />
        </el-form-item>
        <el-form-item label="Bootstrap Servers" prop="bootstrapServers">
          <el-input v-model="form.bootstrapServers" placeholder="host1:9092,host2:9092" />
        </el-form-item>

        <!-- ============ 认证方式(判别符驱动) ============ -->
        <el-form-item :label="t('clusterManage.form.authTypeLabel')">
          <div class="auth-type-block">
            <el-radio-group v-model="form.authType" @change="onAuthTypeChange">
              <el-radio-button v-for="opt in AUTH_TYPE_OPTIONS" :key="opt.value" :value="opt.value">
                {{ t(opt.labelKey) }}
              </el-radio-button>
            </el-radio-group>
            <div class="form-hint">{{ currentAuthHint }}</div>
          </div>
        </el-form-item>

        <!-- 传输加密开关:NONE / PASSWORD / OAUTH 有效;MTLS 恒加密 -->
        <template v-if="usesTlsToggle">
          <el-form-item :label="t('clusterManage.form.tlsLabel')">
            <el-switch v-model="form.tlsEnabled" />
            <span class="form-hint">{{ form.tlsEnabled ? t('clusterManage.form.tlsOn') : t('clusterManage.form.tlsOff') }}</span>
          </el-form-item>
          <el-alert
            v-if="!form.tlsEnabled"
            :closable="false"
            class="auth-alert"
            :description="t('clusterManage.form.plaintextWarnDesc')"
            show-icon
            :title="t('clusterManage.form.plaintextWarnTitle')"
            type="warning"
          />
        </template>

        <el-form-item v-if="showsHostnameToggle" :label="t('clusterManage.form.verifyHostnameLabel')">
          <el-switch v-model="form.verifyHostname" />
          <span class="form-hint">{{ t('clusterManage.form.verifyHostnameHint') }}</span>
        </el-form-item>
        <el-alert
          v-if="showsHostnameToggle && !form.verifyHostname"
          :closable="false"
          class="auth-alert"
          :description="t('clusterManage.form.verifyHostnameOffDesc')"
          show-icon
          :title="t('clusterManage.form.verifyHostnameOffTitle')"
          type="error"
        />

        <!-- ============ 用户名口令 ============ -->
        <template v-if="form.authType === 'PASSWORD'">
          <el-form-item :label="t('clusterManage.form.saslLabel')">
            <el-select v-model="form.saslMechanism" style="width: 100%">
              <el-option v-for="m in SASL_MECHANISMS" :key="m" :label="m" :value="m" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('clusterManage.form.usernameLabel')">
            <el-input v-model="form.username" autocomplete="off" />
          </el-form-item>
          <el-form-item :label="t('clusterManage.form.passwordLabel')">
            <el-input
              v-model="form.password"
              :disabled="isCleared(form, 'password')"
              :placeholder="t(secretPlaceholder(isEditing, presence?.password === true))"
              autocomplete="new-password"
              show-password
              type="password"
            >
              <template #append>
                <el-button @click="toggleCleared(form, 'password')">
                  {{ isCleared(form, 'password') ? t('clusterManage.form.uncleared') : t('clusterManage.form.clear') }}
                </el-button>
              </template>
            </el-input>
          </el-form-item>
        </template>

        <!-- ============ mTLS 客户端证书 ============ -->
        <template v-if="form.authType === 'MTLS'">
          <el-form-item :label="t('clusterManage.form.certLabel')">
            <div class="pem-field">
              <el-input
                v-model="form.sslClientCertPem"
                :autosize="{ minRows: 3, maxRows: 8 }"
                :disabled="isCleared(form, 'sslClientCertPem')"
                :placeholder="t(secretPlaceholder(isEditing, presence?.clientCert === true))"
                type="textarea"
              />
              <div class="pem-actions">
                <el-button size="small" @click="pickFile('sslClientCertPem')">{{ t('clusterManage.form.importFile') }}</el-button>
                <!--
                  刻意不提供"清除证书":mTLS 没有客户端证书就不再是 mTLS(后端也会拒绝
                  提交空证书)。要摘掉证书请改选其他认证方式 —— 这正是判别符模式的好处。
                -->
                <span v-if="isEditing && presence?.clientCert" class="form-hint">{{ t('clusterManage.form.certStoredHint') }}</span>
                <span v-if="pemError('cert', form.sslClientCertPem)" class="pem-error">
                  {{ t(pemError('cert', form.sslClientCertPem)!) }}
                </span>
              </div>
            </div>
          </el-form-item>

          <el-form-item :label="t('clusterManage.form.keyLabel')">
            <div class="pem-field">
              <el-input
                v-model="form.sslClientKeyPem"
                :autosize="{ minRows: 3, maxRows: 8 }"
                :disabled="isCleared(form, 'sslClientKeyPem')"
                :placeholder="t(secretPlaceholder(isEditing, presence?.clientKey === true))"
                type="textarea"
              />
              <div class="pem-actions">
                <el-button size="small" @click="pickFile('sslClientKeyPem')">{{ t('clusterManage.form.importFile') }}</el-button>
                <!-- 同上:私钥不可"清除"(mTLS 必需),换认证方式才是正解 -->
                <span v-if="isEditing && presence?.clientKey" class="form-hint">{{ t('clusterManage.form.keyStoredHint') }}</span>
              </div>
              <div class="form-hint">
                {{ t('clusterManage.form.pkcs8Hint') }}
              </div>
              <span v-if="pemError('key', form.sslClientKeyPem)" class="pem-error">
                {{ t(pemError('key', form.sslClientKeyPem)!) }}
              </span>
            </div>
          </el-form-item>

          <el-form-item v-if="isEncryptedKey(form.sslClientKeyPem) || presence?.clientKeyPassword" :label="t('clusterManage.form.keyPasswordLabel')">
            <el-input
              v-model="form.sslClientKeyPassword"
              :disabled="isCleared(form, 'sslClientKeyPassword')"
              :placeholder="t(secretPlaceholder(isEditing, presence?.clientKeyPassword === true))"
              autocomplete="new-password"
              show-password
              type="password"
            >
              <template #append>
                <el-button @click="toggleCleared(form, 'sslClientKeyPassword')">
                  {{ isCleared(form, 'sslClientKeyPassword') ? t('clusterManage.form.uncleared') : t('clusterManage.form.clear') }}
                </el-button>
              </template>
            </el-input>
          </el-form-item>

          <el-form-item :label="t('clusterManage.form.caLabel')">
            <div class="pem-field">
              <el-input
                v-model="form.sslTrustCertsPem"
                :autosize="{ minRows: 2, maxRows: 6 }"
                :disabled="isCleared(form, 'sslTrustCertsPem')"
                :placeholder="t(secretPlaceholder(isEditing, presence?.trustCerts === true))"
                type="textarea"
              />
              <div class="pem-actions">
                <el-button size="small" @click="pickFile('sslTrustCertsPem')">{{ t('clusterManage.form.importFile') }}</el-button>
                <el-button size="small" @click="toggleCleared(form, 'sslTrustCertsPem')">
                  {{ isCleared(form, 'sslTrustCertsPem') ? t('clusterManage.form.uncleared') : t('clusterManage.form.clearCa') }}
                </el-button>
                <span class="form-hint">{{ t('clusterManage.form.caHint') }}</span>
              </div>
              <span v-if="pemError('trust', form.sslTrustCertsPem)" class="pem-error">
                {{ t(pemError('trust', form.sslTrustCertsPem)!) }}
              </span>
            </div>
          </el-form-item>
        </template>

        <!-- ============ OAuth 2.0 ============ -->
        <template v-if="form.authType === 'OAUTH'">
          <el-form-item :label="t('clusterManage.form.tokenUrlLabel')">
            <el-input v-model="form.oauthTokenUrl" placeholder="https://idp.example.com/oauth2/token" />
          </el-form-item>
          <el-form-item :label="t('clusterManage.form.clientIdLabel')">
            <el-input v-model="form.oauthClientId" autocomplete="off" />
          </el-form-item>
          <el-form-item :label="t('clusterManage.form.clientSecretLabel')">
            <el-input
              v-model="form.oauthClientSecret"
              :disabled="isCleared(form, 'oauthClientSecret')"
              :placeholder="t(secretPlaceholder(isEditing, presence?.oauthClientSecret === true))"
              autocomplete="new-password"
              show-password
              type="password"
            >
              <template #append>
                <el-button @click="toggleCleared(form, 'oauthClientSecret')">
                  {{ isCleared(form, 'oauthClientSecret') ? t('clusterManage.form.uncleared') : t('clusterManage.form.clear') }}
                </el-button>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item :label="t('clusterManage.form.scopeLabel')">
            <el-input v-model="form.oauthScope" :placeholder="t('clusterManage.form.scopePlaceholder')" />
          </el-form-item>
          <div class="form-hint auth-hint-block">
            {{ t('clusterManage.form.oauthHint') }}
          </div>
        </template>

        <!-- ============ 自定义逃生舱 ============ -->
        <template v-if="form.authType === 'CUSTOM'">
          <el-form-item :label="t('clusterManage.form.protocolLabel')">
            <el-select v-model="form.customProtocol" style="width: 100%">
              <el-option v-for="p in CUSTOM_PROTOCOLS" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('clusterManage.form.saslLabel')">
            <el-input v-model="form.customMechanism" :placeholder="t('clusterManage.form.customMechanismPlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('clusterManage.form.jaasLabel')">
            <div class="pem-field">
              <el-input
                v-model="form.customJaas"
                :autosize="{ minRows: 3, maxRows: 10 }"
                :disabled="isCleared(form, 'customJaas')"
                :placeholder="t(secretPlaceholder(isEditing, presence?.customJaas === true))"
                type="textarea"
              />
              <div class="pem-actions">
                <el-button size="small" @click="pickFile('customJaas')">{{ t('clusterManage.form.importFile') }}</el-button>
                <el-button size="small" @click="toggleCleared(form, 'customJaas')">
                  {{ isCleared(form, 'customJaas') ? t('clusterManage.form.uncleared') : t('clusterManage.form.clearJaas') }}
                </el-button>
                <span class="form-hint">{{ t('clusterManage.form.jaasHint') }}</span>
              </div>
            </div>
          </el-form-item>
          <el-form-item :label="t('clusterManage.form.propsLabel')">
            <div class="props-editor">
              <div v-for="(row, idx) in form.customProps" :key="idx" class="prop-row">
                <el-input v-model="row.key" :placeholder="t('clusterManage.form.propKeyPlaceholder')" @input="markPropsDirty" />
                <el-input v-model="row.value" :placeholder="t('clusterManage.form.propValuePlaceholder')" @input="markPropsDirty" />
                <el-button link size="small" type="danger" @click="removeProp(idx)">{{ t('common.remove') }}</el-button>
              </div>
              <el-button size="small" @click="addProp">{{ t('clusterManage.form.addProp') }}</el-button>
              <div class="form-hint">
                {{ t('clusterManage.form.propsHint', {reserved: RESERVED_CUSTOM_PROP_KEYS.join(' / ')}) }}
                <span v-if="presence?.customProps">{{ t('clusterManage.form.propsStored') }}</span>
              </div>
              <div class="form-hint">
                {{ t('clusterManage.form.propsJarHint') }}
              </div>
            </div>
          </el-form-item>
        </template>

        <!-- 派生结果提示:让人一眼看出实际会用什么协议/机制建连 -->
        <el-form-item :label="t('clusterManage.form.derivedLabel')">
          <el-tag effect="plain" type="info">{{ derivedDisplayText }}</el-tag>
        </el-form-item>

        <el-form-item :label="t('clusterManage.form.zkLabel')">
          <el-input v-model="form.zkConnectString" :placeholder="t('clusterManage.form.zkPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('clusterManage.form.enabledLabel')">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item :label="t('clusterManage.form.archiveLabel')">
          <el-switch v-model="form.archiveEnabled" />
          <span class="form-hint">{{ t('clusterManage.form.archiveHint') }}</span>
        </el-form-item>
        <el-form-item v-if="form.archiveEnabled" :label="t('clusterManage.form.retentionLabel')">
          <el-input-number v-model="form.archiveRetentionDays" :max="3650" :min="1" />
        </el-form-item>
      </el-form>

      <!-- 隐藏的文件选择器:PEM / JAAS 导入共用(见 pickFile) -->
      <input
        ref="fileInputRef"
        accept=".pem,.crt,.cer,.key,.txt,.conf,.jaas"
        class="hidden-file-input"
        type="file"
        @change="onFilePicked"
      />

      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button :loading="testing" @click="testConnection">{{ t('clusterManage.form.testConnection') }}</el-button>
        <el-button :loading="saving" type="primary" @click="submit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import {computed, onBeforeUnmount, onMounted, reactive, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage} from 'element-plus'
import {
  type ClusterAuthType,
  type ClusterSummary,
  type ClusterUpsertPayload,
  connectCluster,
  createCluster,
  type CredentialPresence,
  deleteCluster,
  disconnectCluster,
  updateCluster,
  validateCluster,
} from '../api'
import {injectGlobalState} from '../composables/useGlobalState'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import {useResponsive} from '../composables/useResponsive'
import {
  AUTH_TYPE_OPTIONS,
  buildClusterPayload,
  buildValidatePayload,
  type ClusterAuthForm,
  CUSTOM_PROTOCOLS,
  defaultClusterForm,
  derivedProtocolDisplay,
  formFromSummary,
  type FormMessage,
  isCleared,
  isEncryptedKey,
  pemHeaderError,
  RESERVED_CUSTOM_PROP_KEYS,
  SASL_MECHANISMS,
  type SecretField,
  secretPlaceholder,
  toggleCleared,
  validateAuthForm,
} from './clusterForm'

/**
 * 集群管理页。
 *
 * 表单侧的变化:T09 把「安全协议 + SASL 机制」两段式换成「认证方式判别符 + 动态字段区」
 * (NONE / PASSWORD / MTLS / OAUTH / CUSTOM),并把"表单↔请求体"的映射与前端预检
 * 抽到同目录的 `clusterForm.ts`(纯函数,可单测)。
 *
 * 列表数据复用全局状态里的 clusters(与切换器同源,操作后 loadClusters 双端同步)。
 */
const {clusters, clustersLoaded, loadClusters, refreshStatuses} = injectGlobalState()
const {confirm} = useCrudConfirm()
const {isNarrow} = useResponsive()
const {t} = useI18n()
const route = useRoute()
const router = useRouter()

/** FormMessage（clusterForm 纯函数返回的 key+参数）→ 已翻译文本。 */
const fmtMsg = (m: FormMessage) => (m.params ? t(m.key, m.params) : t(m.key))

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

/**
 * 首次引导模式:由欢迎弹框经 ?new=1 跳入。
 * 差异行为:新建保存成功后自动发起连接并返回仪表盘(引导闭环);
 * 常规入口保持原行为(保存后手动点"连接")。对话框关闭即退出引导模式。
 */
const fromGuide = ref(false)

/** 正在编辑集群的"凭据存在性"位(决定占位文案);新建时为 null。 */
const presence = ref<CredentialPresence | null>(null)

const isEditing = computed(() => editingId.value != null)

const reload = async () => {
  loading.value = true
  try {
    await loadClusters()
  } catch (e: any) {
    ElMessage.error(t('clusterManage.page.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

// ---- 状态圆点 / 徽标(与 ClusterSwitcher 同口径) ----

const dotColor = (cl: ClusterSummary) => {
  switch (cl.displayState) {
    case 'ONLINE':
      return 'var(--el-color-success)'
    case 'CONNECTING':
      return 'var(--el-color-warning)'
    default:
      return cl.errorSummary ? 'var(--el-color-danger)' : 'var(--el-text-color-disabled)'
  }
}

const stateTagType = (s: string) =>
  s === 'ONLINE' ? 'success' : s === 'CONNECTING' ? 'warning' : 'info'

const stateText = (s: string) =>
  s === 'ONLINE'
    ? t('clusterManage.state.online')
    : s === 'CONNECTING'
      ? t('clusterManage.state.connecting')
      : t('common.offline')

const errorTooltip = (cl: ClusterSummary) =>
  cl.errorSummary ? t('clusterManage.errorTooltip', {at: cl.errorAt ? `（${cl.errorAt}）` : '', msg: cl.errorSummary}) : ''

/** 列表里的认证方式标签（判别符 → 翻译文案）。 */
const authLabel = (authType?: ClusterAuthType) => {
  const opt = AUTH_TYPE_OPTIONS.find((o) => o.value === (authType ?? 'NONE'))
  return opt ? t(opt.labelKey) : t('clusterManage.auth.none.label')
}

// ---- 连接 / 断开 + 轮询 ----

let pollTimer: number | null = null

const stopPolling = () => {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}
onBeforeUnmount(stopPolling)

/** 连接是异步的:立即返回 CONNECTING,这里轮询直到态稳定(上限 30s 防悬挂) */
const startPolling = (id: number) => {
  stopPolling()
  const started = Date.now()
  pollTimer = window.setInterval(async () => {
    try {
      await refreshStatuses()
    } catch {
      // 轮询失败不终止:下一轮再试
    }
    const cl = clusters.value.find((c) => c.id === id)
    if (!cl || cl.displayState !== 'CONNECTING' || Date.now() - started > 30000) {
      stopPolling()
      if (cl && cl.displayState === 'OFFLINE' && cl.errorSummary) {
        ElMessage.error(t('clusterManage.connectFailed', {name: cl.name, msg: cl.errorSummary}))
      }
    }
  }, 2000)
}

const handleConnect = async (cl: ClusterSummary) => {
  try {
    await connectCluster(cl.id)
    ElMessage.info(t('clusterManage.connecting', {name: cl.name}))
    await refreshStatuses()
    startPolling(cl.id)
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

const handleDisconnect = async (cl: ClusterSummary) => {
  try {
    await disconnectCluster(cl.id)
    ElMessage.success(t('clusterManage.disconnected', {name: cl.name}))
    await refreshStatuses()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

// ---- 删除 ----

const handleDelete = async (cl: ClusterSummary) => {
  const ok = await confirm(
    t('clusterManage.confirmDeleteMsg', {name: cl.name}),
    t('clusterManage.confirmDeleteTitle'),
    'error',
  )
  if (!ok) return
  try {
    await deleteCluster(cl.id)
    ElMessage.success(t('clusterManage.deleted', {name: cl.name}))
    await loadClusters()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

// ---- 新建 / 编辑表单 ----

const form = reactive<ClusterAuthForm>(defaultClusterForm())

const rules: FormRules = {
  name: [{required: true, message: t('clusterManage.form.ruleName'), trigger: 'blur'}],
  bootstrapServers: [{required: true, message: t('clusterManage.form.ruleBootstrap'), trigger: 'blur'}],
  archiveRetentionDays: [{required: true, message: t('clusterManage.form.ruleRetention'), trigger: 'blur'}],
}

/** 当前认证方式的说明文案。 */
const currentAuthHint = computed(
  () => {
    const opt = AUTH_TYPE_OPTIONS.find((o) => o.value === form.authType)
    return opt ? t(opt.hintKey) : ''
  },
)

/** 派生结果展示（实际生效的协议/机制）：纯函数给结构，视图层翻译"无认证"装饰。 */
const derivedDisplayText = computed(() => {
  const d = derivedProtocolDisplay(form)
  const shown = d.noAuth ? t('clusterManage.form.noAuthProtocol') : d.protocol
  return d.mechanism === '' ? shown : `${shown} + ${d.mechanism}`
})

/** 传输加密开关的可见性:MTLS 恒加密,不显示开关。 */
const usesTlsToggle = computed(() => form.authType !== 'MTLS')

/** 主机名校验开关的可见性:只有真正走 TLS 时才谈得上。 */
const showsHostnameToggle = computed(
  () => form.authType === 'MTLS' || (usesTlsToggle.value && form.tlsEnabled),
)

const onAuthTypeChange = () => {
  // 切到 mTLS 时同步打开"传输加密",避免表单自相矛盾(MTLS 恒为 SSL)
  if (form.authType === 'MTLS') {
    form.tlsEnabled = true
  }
}

/** PEM 片段的字段级预检(blur 时提示,不阻断提交 —— 最终以后端 40001 为准)。 */
const pemError = pemHeaderError

const markPropsDirty = () => {
  form.customPropsDirty = true
}

const addProp = () => {
  form.customPropsDirty = true
  form.customProps.push({key: '', value: ''})
}

const removeProp = (idx: number) => {
  form.customPropsDirty = true
  form.customProps.splice(idx, 1)
}

// ---- 文件导入(PEM / JAAS) ----

const pendingImport = ref<SecretField | null>(null)

const pickFile = (target: SecretField) => {
  pendingImport.value = target
  fileInputRef.value?.click()
}

/**
 * 读文件填入目标字段。
 *
 * 只填入不提交(用户仍可手改),并按"填了新值"处理:取消该字段的"清除"标记。
 */
const onFilePicked = async (ev: Event) => {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  const target = pendingImport.value
  // 清空 input:否则连续导入同一个文件不会触发 change
  input.value = ''
  pendingImport.value = null
  if (!file || !target) return
  if (file.size > 256 * 1024) {
    ElMessage.error(t('clusterManage.fileTooLarge'))
    return
  }
  try {
    const text = await file.text()
    ;(form as unknown as Record<string, string>)[target] = text.trim()
    if (isCleared(form, target)) {
      toggleCleared(form, target)
    }
  } catch (e: any) {
    ElMessage.error(t('clusterManage.fileReadFailed', {msg: e?.message ?? ''}))
  }
}

// ---- 测试连接 ----

/** 把后端的错误码翻译成用户能直接照做的提示。 */
const describeValidateError = (e: any): string => {
  switch (e?.code) {
    case 40001:
      return t('clusterManage.validateErr.badRequest', {msg: e.message})
    case 50302:
      return t('clusterManage.validateErr.timeout', {msg: e.message})
    case 50001:
      return t('clusterManage.validateErr.handshakeFailed', {msg: e.message})
    case 40404:
      return t('clusterManage.validateErr.clusterGone')
    default:
      return e?.message ?? t('clusterManage.validateErr.fallback')
  }
}

const testConnection = async () => {
  const errors = validateAuthForm(form, isEditing.value)
  if (errors.length > 0) {
    ElMessage.error(errors.map(fmtMsg).join('；'))
    return
  }
  testing.value = true
  try {
    // 编辑态提交 clusterId:未修改的凭据由后端从库中补全,无需重输
    const res = await validateCluster(
      buildValidatePayload(form, {editing: isEditing.value}, editingId.value),
    )
    ElMessage.success(t('clusterManage.testOk', {n: res.data.brokerCount, ms: res.data.elapsedMs}))
  } catch (e: any) {
    ElMessage.error(describeValidateError(e))
  } finally {
    testing.value = false
  }
}

// ---- 打开 / 提交 ----

const openCreate = () => {
  editingId.value = null
  presence.value = null
  Object.assign(form, defaultClusterForm())
  dialogVisible.value = true
}

const openEdit = (cl: ClusterSummary) => {
  editingId.value = cl.id
  presence.value = cl.credentialPresence ?? null
  Object.assign(form, formFromSummary(cl))
  dialogVisible.value = true
}

const submit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  // 认证规则由纯函数给出可读文案(与后端 ClusterUpsertValidator 对齐)
  const errors = validateAuthForm(form, isEditing.value)
  if (errors.length > 0) {
    ElMessage.error(errors.map(fmtMsg).join('；'))
    return
  }

  const payload: ClusterUpsertPayload = buildClusterPayload(form, {editing: isEditing.value})

  saving.value = true
  try {
    if (editingId.value == null) {
      const created = await createCluster(payload)
      // 引导模式:用户在欢迎流程里刚测完连接,保存后直接发起连接并回仪表盘,
      // 形成「添加 → 连接 → 看到数据」的闭环;连接失败不阻断,列表里可手动重试。
      if (fromGuide.value) {
        fromGuide.value = false
        dialogVisible.value = false
        await loadClusters()
        try {
          const id = created.data?.id
          if (id != null) {
            await connectCluster(id)
            ElMessage.info(t('clusterManage.connecting', {name: payload.name}))
            startPolling(id)
          }
        } catch (e: any) {
          ElMessage.warning(t('clusterManage.guideConnectFailed', {msg: e.message}))
        }
        await router.replace('/')
        return
      }
      ElMessage.success(t('clusterManage.created', {name: payload.name}))
    } else {
      await updateCluster(editingId.value, payload)
      ElMessage.success(t('clusterManage.saved'))
    }
    dialogVisible.value = false
    await loadClusters()
  } catch (e: any) {
    // 40001 = 名称冲突 / 认证校验失败等;40404 = 编辑时集群已被删
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await reload()
  // 首次引导:?new=1 = 从欢迎弹框跳入,自动打开新建集群对话框并清掉 query
  // (防止刷新/回退重复弹)。
  if (route.query.new === '1') {
    fromGuide.value = true
    router.replace({path: '/clusters'})
    openCreate()
  }
})

// 引导模式随对话框关闭(取消/遮罩)结束 —— 用户留在本页,可随时手动再建
watch(dialogVisible, (v) => {
  if (!v) fromGuide.value = false
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-actions {
  display: flex;
  gap: 8px;
}
.name-cell {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.state-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.page-hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.form-hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.auth-type-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}
.auth-type-block .form-hint {
  margin-left: 0;
}
/* 提示条与表单标签对齐:窄档 label-position 变 top,固定左边距会错位,故只留上下边距 */
.auth-alert {
  margin: 0 0 14px;
}
.auth-hint-block {
  margin: 0 0 14px;
  line-height: 1.6;
}
.pem-field {
  width: 100%;
}
.pem-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
}
.pem-error {
  font-size: 12px;
  color: var(--el-color-danger);
}
.props-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.prop-row {
  display: flex;
  gap: 8px;
}
.hidden-file-input {
  display: none;
}
</style>
