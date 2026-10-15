<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 20px">
      <template #header>
        <div class="al-header">
          <span style="font-weight: 600">{{ t('inspection.alerts.title') }}</span>
          <span class="al-hint">{{ t('inspection.alerts.globalHint') }}</span>
          <div class="al-actions">
            <el-button
              :disabled="selected.length === 0"
              :loading="acking"
              size="small"
              @click="onBatchAck"
            >
              {{ t('inspection.alerts.batchAck') }}
            </el-button>
            <el-button :icon="RefreshIcon" :loading="loading" text @click="load">
              {{ t('inspection.rules.refresh') }}
            </el-button>
          </div>
        </div>
      </template>

      <el-form :inline="true" :model="filters">
        <el-form-item>
          <el-select v-model="filters.state" :placeholder="t('inspection.alerts.state')" clearable style="width: 130px">
            <el-option :label="t('inspection.alerts.filters.firing')" value="FIRING" />
            <el-option :label="t('inspection.alerts.filters.recovered')" value="RECOVERED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="filters.severity" :placeholder="t('inspection.alerts.severity')" clearable style="width: 130px">
            <el-option label="INFO" value="INFO" />
            <el-option label="WARN" value="WARN" />
            <el-option label="CRITICAL" value="CRITICAL" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="filters.clusterId" :placeholder="t('inspection.alerts.cluster')" clearable style="width: 180px">
            <el-option v-for="c in clusters" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="filters.acknowledged" :placeholder="t('inspection.alerts.ack')" clearable style="width: 130px">
            <el-option :label="t('inspection.alerts.filters.unread')" :value="false" />
            <el-option :label="t('inspection.alerts.filters.acknowledged')" :value="true" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">{{ t('common.search') }}</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="items"
        style="width: 100%"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column :label="t('inspection.alerts.severity')" width="110">
          <template #default="{row}">
            <el-tag :type="severityTag(row.severity)" size="small">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('inspection.alerts.state')" width="110">
          <template #default="{row}">
            <el-tag :type="row.state === 'FIRING' ? 'danger' : 'success'" size="small">
              {{ t(`inspection.state.${row.state}`) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('inspection.alerts.cluster')" width="160">
          <template #default="{row}">{{ row.clusterName || row.clusterId }}</template>
        </el-table-column>
        <el-table-column :label="t('inspection.alerts.scope')" prop="scopeId" width="180" />
        <el-table-column :label="t('inspection.alerts.alertTitle')" min-width="220" prop="title" />
        <el-table-column :label="t('inspection.alerts.firedAt')" width="200">
          <template #default="{row}">{{ formatDateTime(row.firedAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('inspection.alerts.ackAt')" width="200">
          <template #default="{row}">{{ formatDateTime(row.acknowledgedAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('inspection.alerts.actions')" fixed="right" width="180">
          <template #default="{row}">
            <el-button size="small" text @click="openDetail(row)">{{ t('inspection.alerts.detail') }}</el-button>
            <el-button
              :disabled="Boolean(row.acknowledgedAt)"
              size="small"
              text
              type="primary"
              @click="onAck(row)"
            >
              {{ row.acknowledgedAt ? t('inspection.alerts.acknowledged') : t('inspection.alerts.ack') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <span class="al-empty">{{ t('inspection.alerts.empty') }}</span>
        </template>
      </el-table>

      <div class="al-pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" :title="t('inspection.alerts.detail')" size="560px">
      <div v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('inspection.alerts.severity')">
            {{ detail.alert.severity }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('inspection.alerts.state')">
            {{ t(`inspection.state.${detail.alert.state}`) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('inspection.alerts.cluster')">
            {{ detail.alert.clusterName || detail.alert.clusterId }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('inspection.alerts.scope')">
            {{ detail.alert.scopeId }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('inspection.alerts.alertTitle')">
            {{ detail.alert.title }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('inspection.alerts.firedAt')">
            {{ formatDateTime(detail.alert.firedAt) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('inspection.alerts.recoveredAt')">
            {{ formatDateTime(detail.alert.recoveredAt) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('inspection.alerts.ackBy')">
            {{ detail.alert.acknowledgedBy || '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="al-message">{{ detail.alert.message }}</div>

        <el-divider content-position="left">{{ t('inspection.alerts.dispatchLogs') }}</el-divider>
        <el-table :data="dispatchLogs" size="small">
          <el-table-column :label="t('inspection.channels.name')" min-width="140" prop="channelName" />
          <el-table-column :label="t('inspection.alerts.state')" width="110">
            <template #default="{row}">
              <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">
                {{ t(`inspection.state.${row.status}`) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('inspection.alerts.errorMessage')" min-width="180" prop="errorMessage" />
          <template #empty>
            <span class="al-empty">{{ t('inspection.alerts.noDispatch') }}</span>
          </template>
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script lang="ts" setup>
import {computed, onMounted, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {Refresh as RefreshIcon} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {
  ackAlert,
  type AlertDetail,
  type AlertDispatchLogItem,
  type AlertRecordItem,
  countAlerts,
  getAlert,
  getAlerts,
} from '../api/inspection'
import {injectGlobalState} from '../composables/useGlobalState'
import {formatDateTime} from '../composables/useFormat'
import {ApiError} from '../api'

const {t} = useI18n()
const route = useRoute()
const {clusters} = injectGlobalState()

const loading = ref(false)
const acking = ref(false)
const items = ref<AlertRecordItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filters = ref<{state?: string; severity?: string; clusterId?: number; acknowledged?: boolean}>({})
const selected = ref<AlertRecordItem[]>([])

const detailVisible = ref(false)
const detail = ref<AlertDetail | null>(null)

const dispatchLogs = computed<AlertDispatchLogItem[]>(() => detail.value?.dispatchLogs || [])

const severityTag = (severity?: string) => {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARN') return 'warning'
  return 'info'
}

const queryParams = () => ({
  state: filters.value.state,
  severity: filters.value.severity,
  clusterId: filters.value.clusterId,
  acknowledged: filters.value.acknowledged,
  page: page.value,
  size: size.value,
})

const load = async () => {
  loading.value = true
  try {
    const [listRes, countRes] = await Promise.all([
      getAlerts(queryParams()),
      countAlerts({
        state: filters.value.state,
        severity: filters.value.severity,
        clusterId: filters.value.clusterId,
        acknowledged: filters.value.acknowledged,
      }),
    ])
    items.value = listRes.data || []
    total.value = countRes.data || 0
  } catch (e: any) {
    ElMessage.error(t('inspection.alerts.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

const onSelectionChange = (rows: AlertRecordItem[]) => {
  selected.value = rows
}

const onAck = async (row: AlertRecordItem) => {
  try {
    await ackAlert(row.id)
  } catch (e: any) {
    // 40904（已确认）视为成功：与 onBatchAck / 通知铃铛 ackAll 同口径
    if (!(e instanceof ApiError && e.code === 40904)) {
      ElMessage.error(t('inspection.alerts.ackFailed', {msg: e.message}))
      return
    }
  }
  ElMessage.success(t('inspection.alerts.ackSuccess'))
  await load()
}

/**
 * 批量确认：后端无批量端点，前端逐条 ack + 并发控制。
 * 40904（已确认）视为成功计入 ok —— 与通知铃铛 ackAll 同口径，不中断其余请求。
 */
const onBatchAck = async () => {
  const targets = selected.value.filter((row) => !row.acknowledgedAt)
  if (targets.length === 0) return
  acking.value = true
  let ok = 0
  let fail = 0
  const CONCURRENCY = 5
  try {
    for (let i = 0; i < targets.length; i += CONCURRENCY) {
      const batch = targets.slice(i, i + CONCURRENCY)
      const results = await Promise.allSettled(batch.map((row) => ackAlert(row.id)))
      for (const r of results) {
        if (r.status === 'fulfilled') ok++
        else if (r.reason instanceof ApiError && r.reason.code === 40904) ok++
        else fail++
      }
    }
    ElMessage.success(t('inspection.alerts.batchAckDone', {ok, fail}))
    await load()
  } finally {
    acking.value = false
  }
}

const openDetail = async (row: AlertRecordItem) => {
  try {
    detail.value = (await getAlert(row.id)).data
    detailVisible.value = true
  } catch (e: any) {
    ElMessage.error(t('inspection.alerts.detailFailed', {msg: e.message}))
  }
}

/**
 * 通知面板深链：?highlight={id} 命中当页数据时自动开详情；找不到静默忽略。
 * 不改 URL（不 replace / 不清 query）——避免影响筛选状态与浏览器前进后退。
 * load() 失败时 items 为空，find 不到即静默返回，不叠加第二个错误提示。
 */
const openHighlighted = async () => {
  // 数组形态（?highlight=1&highlight=2）按无效处理
  const raw = Array.isArray(route.query.highlight) ? '' : (route.query.highlight ?? '')
  // Number('') === 0、Number(null) === 0：空 query 必须先挡掉，否则会误判成 id=0
  if (raw === '') return
  const id = Number(raw)
  if (!Number.isInteger(id) || id <= 0) return
  const row = items.value.find((a) => a.id === id)
  if (row) await openDetail(row)
}

watch([page, size], load)
onMounted(async () => {
  await load()
  await openHighlighted()
})
</script>

<style scoped>
.al-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.al-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.al-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.al-pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.al-empty {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
.al-message {
  margin-top: 12px;
  padding: 10px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
