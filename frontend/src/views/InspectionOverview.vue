<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 20px">
      <template #header>
        <div class="ov-header">
          <span style="font-weight: 600">{{ t('inspection.overview.title') }}</span>
          <span class="ov-hint">{{ t('inspection.overview.globalHint') }}</span>
          <div class="ov-actions">
            <el-button :icon="RefreshIcon" :loading="loading" text @click="load">
              {{ t('inspection.rules.refresh') }}
            </el-button>
          </div>
        </div>
      </template>

      <div v-if="overview" class="ov-stats">
        <div class="ov-stat">
          <div class="ov-stat-value">{{ overview.totalRules }}</div>
          <div class="ov-stat-label">{{ t('inspection.overview.totalRules') }}</div>
        </div>
        <div class="ov-stat">
          <div class="ov-stat-value">{{ overview.enabledRules }}</div>
          <div class="ov-stat-label">{{ t('inspection.overview.enabledRules') }}</div>
        </div>
        <div class="ov-stat">
          <div class="ov-stat-value ov-stat--danger">{{ overview.firingCount }}</div>
          <div class="ov-stat-label">{{ t('inspection.overview.firing') }}</div>
        </div>
        <div class="ov-stat">
          <div class="ov-stat-value ov-stat--warn">{{ overview.unreadCount }}</div>
          <div class="ov-stat-label">{{ t('inspection.overview.unread') }}</div>
        </div>
        <div class="ov-stat">
          <div class="ov-stat-value">{{ overview.recoveredToday }}</div>
          <div class="ov-stat-label">{{ t('inspection.overview.recoveredToday') }}</div>
        </div>
      </div>

      <el-divider content-position="left">{{ t('inspection.overview.recentAlerts') }}</el-divider>

      <el-table v-loading="loading" :data="recentAlerts" style="width: 100%">
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
        <el-table-column :label="t('inspection.overview.recentAlerts')" min-width="260" prop="title" />
        <el-table-column :label="t('inspection.alerts.firedAt')" width="200">
          <template #default="{row}">{{ formatDateTime(row.firedAt) }}</template>
        </el-table-column>
        <template #empty>
          <span class="ov-empty">{{ t('inspection.overview.noAlert') }}</span>
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script lang="ts" setup>
import {computed, onMounted, ref} from 'vue'
import {useI18n} from 'vue-i18n'
import {Refresh as RefreshIcon} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {type AlertRecordItem, getInspectionOverview, type InspectionOverview} from '../api/inspection'
import {formatDateTime} from '../composables/useFormat'

const {t} = useI18n()

const loading = ref(false)
const overview = ref<InspectionOverview | null>(null)

const recentAlerts = computed<AlertRecordItem[]>(() => overview.value?.recentAlerts || [])

const severityTag = (severity?: string) => {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARN') return 'warning'
  return 'info'
}

const load = async () => {
  loading.value = true
  try {
    overview.value = (await getInspectionOverview()).data
  } catch (e: any) {
    ElMessage.error(t('inspection.overview.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.ov-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ov-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ov-actions {
  margin-left: auto;
}
.ov-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
.ov-stat {
  flex: 1 1 140px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}
.ov-stat-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.ov-stat--danger {
  color: var(--el-color-danger);
}
.ov-stat--warn {
  color: var(--el-color-warning);
}
.ov-stat-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ov-empty {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
</style>
