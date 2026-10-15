<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 20px">
      <template #header>
        <div class="ru-header">
          <span style="font-weight: 600">{{ t('inspection.rules.title') }}</span>
          <span v-if="activeCluster" class="ru-hint">
            {{ t('inspection.rules.clusterHint', {name: activeCluster.name}) }}
          </span>
          <span v-else class="ru-hint">{{ t('inspection.rules.noCluster') }}</span>
          <div class="ru-actions">
            <el-button :icon="RefreshIcon" :loading="loading" text @click="load">
              {{ t('inspection.rules.refresh') }}
            </el-button>
            <el-button :disabled="!activeClusterId" :icon="PlusIcon" type="primary" @click="openCreate">
              {{ t('inspection.rules.create') }}
            </el-button>
          </div>
        </div>
      </template>

      <el-form :inline="true" :model="filters">
        <el-form-item>
          <el-select v-model="filters.enabled" :placeholder="t('inspection.rules.enabled')" clearable style="width: 120px">
            <el-option :label="t('inspection.rules.enabled')" :value="true" />
            <el-option :label="t('inspection.rules.disabled')" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="filters.scope" :placeholder="t('inspection.rules.scope')" clearable style="width: 170px">
            <el-option v-for="s in scopes" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="filters.name"
            :placeholder="t('inspection.rules.name')"
            clearable
            style="width: 180px"
            @keyup.enter="load"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">{{ t('inspection.rules.refresh') }}</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="items" style="width: 100%">
        <el-table-column :label="t('inspection.rules.name')" min-width="160" prop="name" />
        <el-table-column :label="t('inspection.rules.severity')" width="110">
          <template #default="{row}">
            <el-tag :type="severityTag(row.severity)" size="small">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('inspection.rules.scope')" prop="metricScope" width="150" />
        <el-table-column :label="t('inspection.rules.summary')" min-width="220" prop="conditionSummary" />
        <el-table-column :label="t('inspection.rules.lastState')" width="110">
          <template #default="{row}">
            <el-tag v-if="row.lastState" :type="row.lastState === 'FIRING' ? 'danger' : 'success'" size="small">
              {{ t(`inspection.state.${row.lastState}`) }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column :formatter="formatTimeColumn" :label="t('inspection.rules.nextFire')" prop="nextFireAt" width="200" />
        <el-table-column :label="t('inspection.rules.enabled')" width="90">
          <template #default="{row}">
            <el-switch
              :model-value="row.enabled"
              @change="(v: boolean) => onToggle(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('inspection.rules.actions')" fixed="right" width="240">
          <template #default="{row}">
            <el-button :loading="testingId === row.id" size="small" text @click="onTest(row)">
              {{ t('inspection.rules.test') }}
            </el-button>
            <el-button size="small" text @click="openEdit(row)">{{ t('common.edit') }}</el-button>
            <el-button size="small" text type="danger" @click="onDelete(row)">
              {{ t('common.remove') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="ru-pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
        />
      </div>
    </el-card>

    <RuleBuilderDialog
      v-model="dialogVisible"
      :catalog="catalog"
      :channels="channels"
      :cluster-id="activeClusterId || 0"
      :next-fire-at="lastNextFireAt"
      :rule="editingRule"
      :submitting="saving"
      @submit="onSubmit"
    />
  </div>
</template>

<script lang="ts" setup>
import {computed, onMounted, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {Plus as PlusIcon, Refresh as RefreshIcon} from '@element-plus/icons-vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import RuleBuilderDialog from '../components/inspection/RuleBuilderDialog.vue'
import {
  type AlertChannel,
  createRule,
  type CreateRulePayload,
  deleteRule,
  getChannels,
  getMetricCatalog,
  getRule,
  getRules,
  type InspectionRule,
  type MetricCatalogItem,
  type MetricScopeValue,
  type RuleListItem,
  type RuleTestResult,
  testRule,
  toggleRule,
  updateRule,
} from '../api/inspection'
import {injectGlobalState} from '../composables/useGlobalState'

const {t} = useI18n()
const {activeClusterId, activeCluster} = injectGlobalState()

const loading = ref(false)
const saving = ref(false)
const items = ref<RuleListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filters = ref<{enabled?: boolean; scope?: string; name?: string}>({})

const catalog = ref<MetricCatalogItem[]>([])
const channels = ref<AlertChannel[]>([])
/** 通道列表拉取失败原因(成功/未重试为 null):用于区分「真空列表」与「加载失败」 */
const channelsError = ref<string | null>(null)
const dialogVisible = ref(false)
const editingRule = ref<InspectionRule | null>(null)
const lastNextFireAt = ref<string | null>(null)
const testingId = ref<number | null>(null)

const scopes = computed<MetricScopeValue[]>(() => {
  const set = new Set<string>()
  for (const item of catalog.value) set.add(item.scope)
  return Array.from(set) as MetricScopeValue[]
})

const severityTag = (severity?: string) => {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARN') return 'warning'
  return 'info'
}

/**
 * 后端时间列统一是 UTC ISO 串(带 Z);转本地时区可读格式展示。
 * 非法/缺失值原样返回(兼容 '-' 占位)。
 */
const formatTime = (value?: string | null) => {
  if (!value) return '-'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString()
}

/** el-table-column 的 formatter 签名:(row, column, cellValue) => string */
const formatTimeColumn = (row: RuleListItem, _column: unknown, cellValue: string | null) =>
  formatTime(cellValue)

const load = async () => {
  if (!activeClusterId.value) return
  loading.value = true
  try {
    const res = await getRules(activeClusterId.value, {
      enabled: filters.value.enabled,
      scope: filters.value.scope,
      name: filters.value.name,
      page: page.value,
      size: size.value,
    })
    items.value = res.data.items || []
    total.value = res.data.total
  } catch (e: any) {
    ElMessage.error(t('inspection.rules.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

const loadMeta = async () => {
  if (!activeClusterId.value) return
  try {
    catalog.value = (await getMetricCatalog(activeClusterId.value)).data || []
  } catch (e: any) {
    ElMessage.error(t('inspection.rules.loadMetricsFailed', {msg: e.message}))
  }
  try {
    channels.value = (await getChannels()).data || []
    channelsError.value = null
  } catch (e: any) {
    channels.value = []
    channelsError.value = e?.message || t('common.unknownError')
  }
}

const openCreate = () => {
  // 通道列表为空时,对话框里 channelIds 是必填项却无从选择(提交必然被校验拦下),
  // 这里先拦住并给出引导;「真空列表」与「拉取失败」是两回事,分别提示
  if (channels.value.length === 0) {
    if (channelsError.value) {
      ElMessage.error(t('inspection.rules.loadChannelsFailed', {msg: channelsError.value}))
    } else {
      ElMessage.warning(t('inspection.rules.noChannelsHint'))
    }
    return
  }
  editingRule.value = null
  lastNextFireAt.value = null
  dialogVisible.value = true
}

// 详情单独取一次：列表项是概览，编辑需要 conditionJson 与 channelIds
const openEdit = async (row: RuleListItem) => {
  if (!activeClusterId.value) return
  try {
    editingRule.value = (await getRule(activeClusterId.value, row.id)).data
    lastNextFireAt.value = null
    dialogVisible.value = true
  } catch (e: any) {
    ElMessage.error(t('inspection.rules.loadFailed', {msg: e.message}))
  }
}

const onSubmit = async (payload: CreateRulePayload) => {
  if (!activeClusterId.value) return
  saving.value = true
  try {
    const clusterId = activeClusterId.value
    const res = editingRule.value
      ? await updateRule(clusterId, editingRule.value.id, payload)
      : await createRule(clusterId, payload)
    lastNextFireAt.value = res.data.nextFireAt || null
    ElMessage.success(t('inspection.rules.saveSuccess'))
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

const onToggle = async (row: RuleListItem, enabled: boolean) => {
  if (!activeClusterId.value) return
  try {
    await toggleRule(activeClusterId.value, row.id, enabled)
    await load()
  } catch (e: any) {
    ElMessage.error(t('inspection.rules.toggleFailed', {msg: e.message}))
  }
}

const onTest = async (row: RuleListItem) => {
  if (!activeClusterId.value) return
  testingId.value = row.id
  try {
    const result: RuleTestResult = (await testRule(activeClusterId.value, row.id)).data
    const head = result.fired ? t('inspection.rules.fired') : t('inspection.rules.notFired')
    const extra = result.insufficientSamples ? ` / ${t('inspection.rules.insufficient')}` : ''
    const scopesText = (result.firedScopes || []).join(', ')
    ElMessage({
      type: result.fired ? 'warning' : 'success',
      message: `${head}${extra}${scopesText ? ' — ' + scopesText : ''}`,
      duration: 4000,
    })
  } catch (e: any) {
    ElMessage.error(t('inspection.rules.testFailed', {msg: e.message}))
  } finally {
    testingId.value = null
  }
}

const onDelete = async (row: RuleListItem) => {
  try {
    await ElMessageBox.confirm(
      t('inspection.rules.deleteConfirm', {name: row.name}),
      t('inspection.rules.deleteTitle'),
      {type: 'warning'},
    )
  } catch {
    return
  }
  try {
    await deleteRule(activeClusterId.value as number, row.id)
    ElMessage.success(t('inspection.rules.deleted'))
    await load()
  } catch (e: any) {
    ElMessage.error(t('inspection.rules.deleteFailed', {msg: e.message}))
  }
}

// 集群切换后规则页跟随（与 Cluster / Topic 同模式）
watch(activeClusterId, () => {
  page.value = 1
  loadMeta()
  load()
})

watch([page, size], load)

onMounted(() => {
  loadMeta()
  load()
})
</script>

<style scoped>
.ru-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ru-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ru-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.ru-pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
