<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span style="font-weight: 600">{{ t('dashboard.title') }}</span>
          <div class="header-right">
            <span class="poll-hint">{{ t('dashboard.pollHint') }}</span>
            <el-button :loading="loading" size="small" @click="load">{{ t('common.refresh') }}</el-button>
          </div>
        </div>
      </template>

      <el-skeleton v-if="loading && !cards.length" :rows="4" animated />

      <!-- 零集群引导:聚合端点返回空数组 -->
      <el-empty v-else-if="!cards.length" :description="t('dashboard.emptyHint')">
        <el-button type="primary" @click="$router.push('/clusters')">{{ t('dashboard.goAdd') }}</el-button>
      </el-empty>

      <!--
        卡片栅格:CSS Grid auto-fill 连续适配三档,不按档位写死列数。
        宽档 ~4 列 / 中档 2–3 列 / 窄档 1–2 列。
      -->
      <div v-else class="cluster-grid">
        <el-card
          v-for="card in cards"
          :key="card.clusterId"
          :class="{ 'is-clickable': true }"
          class="cluster-card"
          shadow="hover"
          @click="goCluster(card)"
        >
          <template #header>
            <div class="card-head">
              <span class="cluster-name">
                <el-tooltip :content="card.errorSummary || ''" :disabled="!card.errorSummary" placement="top">
                  <span :style="{ background: dotColor(card) }" class="state-dot" />
                </el-tooltip>
                {{ card.name }}
              </span>
              <el-tag :type="stateTagType(card.displayState)" size="small">
                {{ stateText(card.displayState) }}
              </el-tag>
            </div>
          </template>

          <!-- 在线:紧凑指标行(口径与单集群 overview 一致) -->
          <template v-if="card.displayState === 'ONLINE' && card.overview">
            <div class="stat-grid">
              <div class="stat-item">
                <span class="stat-label">Brokers</span>
                <span class="stat-value">{{ card.overview.brokerCount }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">Topics</span>
                <span class="stat-value">{{ card.overview.topicCount }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">{{ t('dashboard.stat.partitions') }}</span>
                <span class="stat-value">{{ card.overview.partitionCount }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">{{ t('dashboard.stat.consumerGroups') }}</span>
                <!-- 含 kafkaviz-archive-<id> 归档消费组(预期行为,不过滤) -->
                <span class="stat-value">{{ card.overview.consumerGroupCount }}</span>
              </div>
              <div class="stat-item">
                <span class="stat-label">URP</span>
                <span :class="card.overview.underReplicatedPartitions > 0 ? 'value-danger' : 'value-ok'" class="stat-value">
                  {{ card.overview.underReplicatedPartitions }}
                </span>
              </div>
              <div class="stat-item">
                <span class="stat-label">{{ t('dashboard.stat.offlinePartitions') }}</span>
                <span :class="card.overview.offlinePartitions > 0 ? 'value-danger' : 'value-ok'" class="stat-value">
                  {{ card.overview.offlinePartitions }}
                </span>
              </div>
            </div>
            <div class="card-foot">
              {{ t('dashboard.logTotalEnterHint', {size: formatBytes(card.overview.totalLogSizeBytes)}) }}
            </div>
          </template>

          <!-- 在线但指标查询失败:单集群失败不拖垮整页 -->
          <template v-else-if="card.displayState === 'ONLINE' && card.errorSummary">
            <el-alert :closable="false" :description="card.errorSummary" show-icon
              :title="t('dashboard.metricsQueryFailed')" type="error" />
            <div class="card-foot">{{ t('dashboard.clickStillEnter') }}</div>
          </template>

          <!-- 连接中 -->
          <template v-else-if="card.displayState === 'CONNECTING'">
            <div class="offline-hint">
              <el-icon class="is-loading"><Loading /></el-icon>
              {{ t('dashboard.connectingHint') }}
            </div>
          </template>

          <!-- 离线:归档消息量 + 历史入口 -->
          <template v-else>
            <div class="stat-grid">
              <div class="stat-item">
                <span class="stat-label">{{ t('dashboard.stat.archivedMessages') }}</span>
                <span class="stat-value">
                  {{ card.archivedMessages == null ? '-' :
                     card.archivedMessages < 0 ? t('dashboard.fetchFailed') : card.archivedMessages.toLocaleString() }}
                </span>
              </div>
            </div>
            <el-alert
              v-if="card.errorSummary"
              :closable="false"
              :title="card.errorSummary"
              show-icon
              style="margin-top: 8px"
              type="error"
            />
            <div class="card-foot">
              {{ card.archivedMessages != null && card.archivedMessages > 0 ? t('dashboard.clickBrowseArchive') : t('dashboard.clickEnterCluster') }}
            </div>
          </template>
        </el-card>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {onBeforeUnmount, onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {Loading} from '@element-plus/icons-vue'
import {getMultiDashboard, type MultiClusterDashboard} from '../api'
import {injectGlobalState} from '../composables/useGlobalState'
import {ElMessage} from 'element-plus'

const {t} = useI18n()

/**
 * 多集群仪表盘:卡片总览 + 10s 轮询 + 点击导航。
 * 聚合端点本身 per-cluster 容错,这里只需处理整体失败(50302 池满等)。
 */
const router = useRouter()
const {setActiveCluster} = injectGlobalState()

const cards = ref<MultiClusterDashboard[]>([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    const res = await getMultiDashboard()
    cards.value = res.data
  } catch (e: any) {
    ElMessage.error(t('dashboard.loadFailed', {msg: e.message}))
  } finally {
    loading.value = false
  }
}

const goCluster = (card: MultiClusterDashboard) => {
  // 当前路由不变式:切换集群后由各视图 watch activeClusterId 自行重取
  setActiveCluster(card.clusterId)
  // 离线集群同样进 /cluster:Cluster 页会展示离线横幅,TopicList 自动回退归档台账
  router.push('/cluster')
}

const dotColor = (card: MultiClusterDashboard) => {
  if (card.displayState === 'ONLINE') {
    return card.errorSummary ? 'var(--el-color-warning)' : 'var(--el-color-success)'
  }
  if (card.displayState === 'CONNECTING') return 'var(--el-color-warning)'
  return card.errorSummary ? 'var(--el-color-danger)' : 'var(--el-text-color-disabled)'
}

const stateTagType = (s: string) =>
  s === 'ONLINE' ? 'success' : s === 'CONNECTING' ? 'warning' : 'info'

const stateText = (s: string) =>
  s === 'ONLINE' ? t('dashboard.state.online') : s === 'CONNECTING' ? t('dashboard.state.connecting') : t('common.offline')

const formatBytes = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(2) + ' MB'
  if (bytes < 1024 * 1024 * 1024 * 1024) return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  return (bytes / 1024 / 1024 / 1024 / 1024).toFixed(2) + ' TB'
}

// ---- 10s 轮询(页面可见时),与 App 壳的状态轮询相互独立(端点不同) ----
let pollTimer: number | null = null

onMounted(() => {
  load()
  pollTimer = window.setInterval(() => {
    if (!document.hidden) load()
  }, 10000)
})

// 页签不可见时也跳过在途轮询的堆积:简单起见,轮询本身每轮检查 hidden
onBeforeUnmount(() => {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.poll-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cluster-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}
/* 点击进入该集群:手型 + 悬停描边反馈(el-card shadow=hover 已有阴影反馈) */
.cluster-card {
  cursor: pointer;
  transition: border-color 0.2s;
}
.cluster-card:hover {
  border-color: var(--el-color-primary);
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cluster-name {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}
.state-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px 16px;
}
.stat-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat-value {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.2;
}
.value-danger {
  color: var(--el-color-danger);
}
.value-ok {
  color: var(--el-color-success);
}
.card-foot {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.offline-hint {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  padding: 12px 0;
}
</style>
