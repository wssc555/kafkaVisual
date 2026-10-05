<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span style="font-weight: 600">{{ t('groupsMeta.settings.title') }}</span>
          <el-tag size="small" type="info">{{ t('groupsMeta.settings.activeTag', {type: activeTypeText}) }}</el-tag>
        </div>
      </template>

      <!-- 已保存未生效横幅:storage.json type 与实际生效类型不一致 -->
      <el-alert
        v-if="info?.restartRequired"
        :closable="false"
        :description="t('groupsMeta.settings.restartRequiredDesc', {saved: typeText(info.type), active: typeText(info.activeType)})"
        show-icon
        style="margin-bottom: 16px"
        :title="t('groupsMeta.settings.restartRequiredTitle')"
        type="warning"
      />

      <el-form
        :label-position="isNarrow ? 'top' : 'right'"
        :model="form"
        label-width="140px"
        style="max-width: 640px"
      >
        <el-form-item :label="t('groupsMeta.settings.typeLabel')">
          <el-radio-group v-model="form.type" @change="onTypeChange">
            <el-radio value="sqlite">{{ t('groupsMeta.settings.sqliteDefault') }}</el-radio>
            <el-radio value="postgresql">PostgreSQL</el-radio>
            <el-radio value="mysql">MySQL</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-alert
          v-if="form.type === 'sqlite'"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          :title="t('groupsMeta.settings.sqliteHint')"
          type="info"
        />

        <template v-else>
          <el-form-item :label="t('groupsMeta.settings.host')" required>
            <el-input v-model="form.host" :placeholder="t('groupsMeta.settings.hostPlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('groupsMeta.settings.port')">
            <el-input-number
              v-model="form.port"
              :max="65535"
              :min="0"
              :precision="0"
              controls-position="right"
            />
            <span class="form-hint">{{ t('groupsMeta.settings.portHint') }}</span>
          </el-form-item>
          <el-form-item :label="t('groupsMeta.settings.database')" required>
            <el-input v-model="form.database" :placeholder="t('groupsMeta.settings.databasePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('groupsMeta.settings.username')" required>
            <el-input v-model="form.username" autocomplete="off" />
          </el-form-item>
          <el-form-item :label="t('groupsMeta.settings.password')">
            <el-input
              v-model="form.password"
              :placeholder="hasStoredPassword ? t('groupsMeta.settings.passwordStored') : t('groupsMeta.settings.passwordEmpty')"
              autocomplete="new-password"
              show-password
              type="password"
            />
          </el-form-item>
          <el-form-item :label="t('groupsMeta.settings.extraParams')">
            <el-input v-model="form.extraParams" :placeholder="t('groupsMeta.settings.extraParamsPlaceholder')" />
          </el-form-item>
        </template>
      </el-form>

      <div class="actions">
        <el-button :loading="testing" plain type="warning" @click="testConnection">
          {{ t('groupsMeta.settings.testConnection') }}
        </el-button>
        <el-tooltip
          :disabled="canSave"
          :content="t('groupsMeta.settings.saveTooltip')"
          placement="top"
        >
          <span>
            <el-button :disabled="!canSave" :loading="saving" type="primary" @click="save">
              {{ t('common.save') }}
            </el-button>
          </span>
        </el-tooltip>
      </div>

      <!-- 测试结果:success / latency / version / error -->
      <el-alert
        v-if="testResult"
        :closable="true"
        :title="testResult.success
          ? t('groupsMeta.settings.testOk', {latency: testResult.latencyMs ?? '-', version: testResult.version ?? '-'})
          : t('groupsMeta.settings.testFailed', {msg: testResult.error ?? t('common.unknownError')})"
        :type="testResult.success ? 'success' : 'error'"
        show-icon
        style="margin-top: 12px"
      />

      <el-divider content-position="left">{{ t('groupsMeta.settings.notesTitle') }}</el-divider>
      <ul class="notes">
        <I18nT keypath="groupsMeta.settings.note1" scope="global" tag="li">
          <template #0><b>{{ t('groupsMeta.settings.note1Bold') }}</b></template>
        </I18nT>
        <I18nT keypath="groupsMeta.settings.note2" scope="global" tag="li">
          <template #0><b>{{ t('groupsMeta.settings.note2Bold') }}</b></template>
        </I18nT>
        <li>{{ t('groupsMeta.settings.note3') }}</li>
        <I18nT keypath="groupsMeta.settings.note4" scope="global" tag="li">
          <template #path><code>{{ info?.configFile || '-' }}</code></template>
        </I18nT>
      </ul>
    </el-card>
  </div>
</template>

<script lang="ts" setup>
import {computed, onMounted, reactive, ref} from 'vue'
import {I18nT, useI18n} from 'vue-i18n'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  getStorageConfig,
  saveStorageConfig,
  type StorageConfigPayload,
  type StorageInfo,
  type StorageTestResult,
  testStorageConfig,
} from '../api'
import {useResponsive} from '../composables/useResponsive'

/**
 * 数据源设置页:
 * SQLite 默认零配置;PG/MySQL 手动配置 + 测试通过才允许保存(与后端 PUT 强制 test 双保险)。
 */
const {isNarrow} = useResponsive()
const {t} = useI18n()

const info = ref<StorageInfo | null>(null)
const loading = ref(false)
const testing = ref(false)
const saving = ref(false)
const testResult = ref<StorageTestResult | null>(null)

const form = reactive<{
  type: string
  host: string
  port: number
  database: string
  username: string
  password: string
  extraParams: string
}>({
  type: 'sqlite',
  host: '',
  port: 0,
  database: '',
  username: '',
  password: '',
  extraParams: '',
})

/** 后端回显的打码哨兵:出现即表示库里已有口令 */
const hasStoredPassword = computed(() => !!info.value && info.value.type !== 'sqlite' && info.value.password === '******')

const activeTypeText = computed(() => typeText(info.value?.activeType || info.value?.type || ''))

const typeText = (t: string | null | undefined) =>
  t === 'postgresql' ? 'PostgreSQL' : t === 'mysql' ? 'MySQL' : t === 'sqlite' ? 'SQLite' : t || '-'

const loadInfo = async () => {
  loading.value = true
  try {
    const res = await getStorageConfig()
    info.value = res.data
    applyInfo(res.data)
  } catch (e: any) {
    ElMessage.error(t('groupsMeta.settings.loadConfigFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

const applyInfo = (data: StorageInfo) => {
  form.type = data.type || 'sqlite'
  form.host = data.host || ''
  form.port = data.port || 0
  form.database = data.database || ''
  form.username = data.username || ''
  // password 是打码哨兵('******')或空串,原样回填:不改就提交哨兵 = 后端保持原口令
  form.password = data.password || ''
  form.extraParams = data.extraParams || ''
  // 载入后重置测试状态:测试结果只对"当前表单值"有效
  testResult.value = null
  testedSnapshot.value = ''
}

const buildPayload = (): StorageConfigPayload => {
  const payload: StorageConfigPayload = {
    type: form.type,
    host: form.type === 'sqlite' ? undefined : form.host.trim() || undefined,
    port: form.type === 'sqlite' ? undefined : form.port || undefined,
    database: form.type === 'sqlite' ? undefined : form.database.trim() || undefined,
    username: form.type === 'sqlite' ? undefined : form.username.trim() || undefined,
    password: form.type === 'sqlite' ? undefined : form.password,
    extraParams: form.type === 'sqlite' ? undefined : form.extraParams.trim() || undefined,
  }
  return payload
}

const onTypeChange = () => {
  // 类型切换后旧的测试结果作废(不同方言连接语义不同)
  testResult.value = null
  testedSnapshot.value = ''
}

/** "测试通过"绑定的是当时这份表单值:此后任意字段改动都会使保存按钮重新禁用 */
const testedSnapshot = ref('')

const snapshot = () => JSON.stringify(buildPayload())

const canSave = computed(() => !!testResult.value?.success && testedSnapshot.value !== '' && testedSnapshot.value === snapshot())

const testConnection = async () => {
  testing.value = true
  testResult.value = null
  try {
    const res = await testStorageConfig(buildPayload())
    testResult.value = res.data
    // 测试失败是正常业务结果(200 + success=false),不抛错
    if (res.data.success) {
      testedSnapshot.value = snapshot()
    }
  } catch (e: any) {
    // 40001 = 配置不完整(host/database/username 缺失)等
    ElMessage.error(e.message)
  } finally {
    testing.value = false
  }
}

const save = async () => {
  if (!canSave.value) return
  saving.value = true
  try {
    const res = await saveStorageConfig(buildPayload())
    // 后端 msg 携带"重启生效"提示,弹窗展示(不取返回值,规避 MessageBoxData 类型退化)
    await ElMessageBox.alert(res.msg || t('groupsMeta.settings.savedFallback'), t('groupsMeta.settings.savedTitle'), {
      confirmButtonText: t('common.gotIt'),
      type: 'success',
    })
    await loadInfo()
  } catch (e: any) {
    // 40001 = 服务端强制 test 未通过(或配置不完整)
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

onMounted(loadInfo)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.actions {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}
.form-hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.notes {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  line-height: 1.9;
}
.notes code {
  word-break: break-all;
}
</style>
