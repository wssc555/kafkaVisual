<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 20px">
      <template #header>
        <div class="ch-header">
          <span style="font-weight: 600">{{ t('inspection.channels.title') }}</span>
          <span class="ch-hint">{{ t('inspection.channels.globalHint') }}</span>
          <div class="ch-actions">
            <el-button :icon="RefreshIcon" :loading="loading" text @click="load">
              {{ t('inspection.rules.refresh') }}
            </el-button>
            <el-button :icon="PlusIcon" type="primary" @click="openCreate">
              {{ t('inspection.channels.create') }}
            </el-button>
          </div>
        </div>
      </template>
      <span class="ch-secret-hint">{{ t('inspection.channels.secretHint') }}</span>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="channels" style="width: 100%">
        <el-table-column :label="t('inspection.channels.name')" min-width="160" prop="name" />
        <el-table-column :label="t('inspection.channels.type')" prop="type" width="120" />
        <el-table-column :label="t('inspection.channels.enabled')" prop="enabled" width="90">
          <template #default="{row}">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? t('inspection.channels.enabled') : t('common.no') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('inspection.channels.actions')" fixed="right" width="260">
          <template #default="{row}">
            <el-button :loading="testingId === row.id" size="small" text @click="onTest(row)">
              {{ t('inspection.channels.test') }}
            </el-button>
            <el-button size="small" text @click="openEdit(row)">{{ t('common.edit') }}</el-button>
            <el-button size="small" text type="danger" @click="onDelete(row)">
              {{ t('common.remove') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? t('inspection.channels.edit') : t('inspection.channels.create')"
      width="640px"
    >
      <el-form :model="form" label-width="120px">
        <el-form-item :label="t('inspection.channels.name')">
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item :label="t('inspection.channels.type')">
          <el-select v-model="form.type" style="width: 200px" @change="onTypeChange">
            <el-option v-for="tp in types" :key="tp" :label="tp" :value="tp" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('inspection.channels.enabled')">
          <el-switch v-model="form.enabled" />
        </el-form-item>

        <template v-if="form.type === 'EMAIL'">
          <el-form-item :label="t('inspection.channels.email.host')">
            <el-input v-model="form.host" />
          </el-form-item>
          <el-form-item :label="t('inspection.channels.email.port')">
            <el-input-number v-model="form.port" :max="65535" :min="1" />
          </el-form-item>
          <el-form-item :label="t('inspection.channels.email.username')">
            <el-input v-model="form.username" />
          </el-form-item>
          <el-form-item :label="t('inspection.channels.email.password')">
            <el-input v-model="form.password" show-password />
          </el-form-item>
          <el-form-item :label="t('inspection.channels.email.to')">
            <el-input v-model="form.to" placeholder="a@b.com,c@d.com" />
          </el-form-item>
          <el-form-item :label="t('inspection.channels.email.cc')">
            <el-input v-model="form.cc" />
          </el-form-item>
          <el-form-item :label="t('inspection.channels.email.ssl')">
            <el-switch v-model="form.ssl" />
          </el-form-item>
          <el-form-item :label="t('inspection.channels.email.starttls')">
            <el-switch v-model="form.starttls" />
          </el-form-item>
        </template>

        <template v-else-if="form.type === 'WEBHOOK'">
          <el-form-item :label="t('inspection.channels.webhook.url')">
            <el-input v-model="form.url" placeholder="https://..." />
          </el-form-item>
          <el-form-item :label="t('inspection.channels.webhook.headers')">
            <el-input v-model="form.headers" :rows="2" placeholder='{"X-Token":"abc"}' type="textarea" />
          </el-form-item>
        </template>

        <template v-else-if="form.type === 'IM_BOT'">
          <el-form-item :label="t('inspection.channels.im.platform')">
            <el-select v-model="form.platform" style="width: 200px">
              <el-option label="DINGTALK" value="DINGTALK" />
              <el-option label="FEISHU" value="FEISHU" />
              <el-option label="WECOM" value="WECOM" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('inspection.channels.im.token')">
            <el-input v-model="form.token" show-password />
          </el-form-item>
          <el-form-item :label="t('inspection.channels.im.secret')">
            <el-input v-model="form.secret" show-password />
          </el-form-item>
        </template>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button :loading="saving" type="primary" @click="submit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import {onMounted, ref} from 'vue'
import {useI18n} from 'vue-i18n'
import {Plus as PlusIcon, Refresh as RefreshIcon} from '@element-plus/icons-vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  createChannel,
  deleteChannel,
  getChannel,
  getChannels,
  testChannel,
  updateChannel,
  type AlertChannel,
  type ChannelTypeValue,
} from '../api/inspection'

const {t} = useI18n()

const loading = ref(false)
const saving = ref(false)
const testingId = ref<number | null>(null)
const channels = ref<AlertChannel[]>([])
const dialogVisible = ref(false)
const editing = ref<AlertChannel | null>(null)

const types: ChannelTypeValue[] = ['INSITE', 'EMAIL', 'WEBHOOK', 'IM_BOT']

const form = ref<{
  name: string
  type: ChannelTypeValue
  enabled: boolean
  host: string
  port: number
  username: string
  password: string
  to: string
  cc: string
  ssl: boolean
  starttls: boolean
  url: string
  headers: string
  platform: string
  token: string
  secret: string
}>({
  name: '',
  type: 'INSITE',
  enabled: true,
  host: '',
  port: 465,
  username: '',
  password: '',
  to: '',
  cc: '',
  ssl: false,
  starttls: false,
  url: '',
  headers: '',
  platform: 'DINGTALK',
  token: '',
  secret: '',
})

const load = async () => {
  loading.value = true
  try {
    channels.value = (await getChannels()).data || []
  } catch (e: any) {
    ElMessage.error(t('inspection.channels.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

const resetForm = () => {
  form.value = {
    name: '',
    type: 'INSITE',
    enabled: true,
    host: '',
    port: 465,
    username: '',
    password: '',
    to: '',
    cc: '',
    ssl: false,
    starttls: false,
    url: '',
    headers: '',
    platform: 'DINGTALK',
    token: '',
    secret: '',
  }
}

const onTypeChange = () => {
  // 换类型 = 换一套配置字段：旧字段留着会被一起提交，这里整体清空
  form.value.password = ''
  form.value.token = ''
  form.value.secret = ''
  form.value.headers = ''
}

const openCreate = () => {
  editing.value = null
  resetForm()
  dialogVisible.value = true
}

const openEdit = async (row: AlertChannel) => {
  try {
    const detail = (await getChannel(row.id)).data
    editing.value = detail
    resetForm()
    form.value.name = detail.name
    form.value.type = detail.type
    form.value.enabled = detail.enabled
    const cfg = (detail.config || {}) as Record<string, unknown>
    // 敏感字段后端回 '******'：填回表单即"保持原值"（三态语义）
    form.value.host = String(cfg.host ?? '')
    form.value.port = Number(cfg.port ?? 465)
    form.value.username = String(cfg.username ?? '')
    form.value.password = String(cfg.password ?? '') === '******' ? '******' : String(cfg.password ?? '')
    form.value.to = String(cfg.to ?? '')
    form.value.cc = String(cfg.cc ?? '')
    form.value.ssl = Boolean(cfg.ssl)
    form.value.starttls = Boolean(cfg.starttls)
    form.value.url = String(cfg.url ?? '')
    form.value.headers = String(cfg.headers ?? '')
    form.value.platform = String(cfg.platform ?? 'DINGTALK')
    form.value.token = String(cfg.token ?? '') === '******' ? '******' : String(cfg.token ?? '')
    form.value.secret = String(cfg.secret ?? '') === '******' ? '******' : String(cfg.secret ?? '')
    dialogVisible.value = true
  } catch (e: any) {
    ElMessage.error(t('inspection.channels.loadFailed', {msg: e.message}))
  }
}

/** 只提交"用户填过的"字段：未填的敏感键不进 config，后端按三态保持原密文 */
const buildConfig = (): Record<string, unknown> => {
  const cfg: Record<string, unknown> = {}
  switch (form.value.type) {
    case 'EMAIL':
      cfg.host = form.value.host
      cfg.port = form.value.port
      cfg.to = form.value.to
      cfg.cc = form.value.cc
      cfg.ssl = form.value.ssl
      cfg.starttls = form.value.starttls
      cfg.username = form.value.username
      if (form.value.password) cfg.password = form.value.password
      break
    case 'WEBHOOK':
      cfg.url = form.value.url
      if (form.value.headers) cfg.headers = form.value.headers
      break
    case 'IM_BOT':
      cfg.platform = form.value.platform
      if (form.value.token) cfg.token = form.value.token
      if (form.value.secret) cfg.secret = form.value.secret
      break
    default:
      break
  }
  return cfg
}

const submit = async () => {
  saving.value = true
  try {
    const config = buildConfig()
    if (editing.value) {
      await updateChannel(editing.value.id, {config, enabled: form.value.enabled})
    } else {
      await createChannel({name: form.value.name, type: form.value.type, config, enabled: form.value.enabled})
    }
    ElMessage.success(t('inspection.channels.saveSuccess'))
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

const onTest = async (row: AlertChannel) => {
  testingId.value = row.id
  try {
    const res = (await testChannel(row.id)).data
    if (res.success) ElMessage.success(t('inspection.channels.testSuccess'))
    else ElMessage.error(t('inspection.channels.testFailed', {msg: res.errorMessage || ''}))
  } catch (e: any) {
    ElMessage.error(t('inspection.channels.testFailed', {msg: e.message}))
  } finally {
    testingId.value = null
  }
}

const onDelete = async (row: AlertChannel) => {
  try {
    await ElMessageBox.confirm(
      t('inspection.channels.deleteConfirm', {name: row.name}),
      t('inspection.channels.deleteTitle'),
      {type: 'warning'},
    )
  } catch {
    return
  }
  try {
    await deleteChannel(row.id)
    ElMessage.success(t('inspection.channels.deleted'))
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.ch-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ch-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ch-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.ch-secret-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
