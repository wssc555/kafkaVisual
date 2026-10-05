<template>
  <div>
    <el-button size="small" style="margin-bottom: 12px" @click="$router.push('/')">
      {{ t('clusterBrowse.page.backToDashboard') }}
    </el-button>

    <!-- 离线横幅:实时信息不可得,但不阻断归档浏览 -->
    <el-alert
      v-if="offline"
      :closable="false"
      :description="t('clusterBrowse.page.offlineAlertDesc')"
      show-icon
      style="margin-bottom: 20px"
      :title="t('clusterBrowse.page.offlineAlertTitle')"
      type="warning"
    />

    <!-- 集群信息卡片(仅在线可用;离线时隐藏,避免 50302 报错噪音) -->
    <el-card v-if="!offline" shadow="never" style="margin-bottom: 20px">
      <template #header>
        <div class="card-header">
          <span style="font-weight: 600">{{ t('clusterBrowse.page.clusterInfoTitle') }}</span>
          <el-button :loading="loadingCluster" size="small" @click="loadCluster">{{ t('common.refresh') }}</el-button>
        </div>
      </template>
      <el-descriptions :column="2" border v-if="clusterInfo">
        <el-descriptions-item label="Cluster ID">{{ clusterInfo.clusterId }}</el-descriptions-item>
        <el-descriptions-item label="Controller ID">{{ clusterInfo.controllerId }}</el-descriptions-item>
        <el-descriptions-item label="Brokers" :span="2">
          <el-tag v-for="b in clusterInfo.brokers" :key="b.id" size="small" style="margin-right: 6px">
            {{ b.host }}:{{ b.port }} (id: {{ b.id }})
          </el-tag>
          <span v-if="!clusterInfo.brokers?.length">{{ t('common.none') }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-skeleton :rows="3" animated v-else />
    </el-card>

    <!-- Topic 列表(离线时自动回退为归档台账,逻辑在 TopicList 内) -->
    <TopicList ref="topicListRef" @create="showCreateDialog = true" @view="goTopic" @view-archive="goArchiveTopic" />
    <CreateTopicDialog v-model:visible="showCreateDialog" :cluster-id="activeClusterId ?? 0" @created="refreshTopics" />
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {type ApiError, ClusterInfo, getClusterInfo} from '../api'
import TopicList from '../components/TopicList.vue'
import CreateTopicDialog from '../components/CreateTopicDialog.vue'
import {injectGlobalState} from '../composables/useGlobalState'
import {ElMessage} from 'element-plus'

const {t} = useI18n()

/**
 * Topic 浏览页:
 * 所有请求带 activeClusterId,切换集群后整体重取;
 * 离线集群:集群信息卡隐藏,topic 列表由 TopicList 回退为归档台账。
 */
const router = useRouter()
const {activeClusterId, activeCluster} = injectGlobalState()

const clusterInfo = ref<ClusterInfo | null>(null)
const loadingCluster = ref(false)
const showCreateDialog = ref(false)
const topicListRef = ref<InstanceType<typeof TopicList> | null>(null)

/** 激活集群是否离线(来自全局状态的连接展示态,轮询自动刷新) */
const offline = computed(() => activeCluster.value?.displayState === 'OFFLINE')

const loadCluster = async () => {
  if (activeClusterId.value == null || offline.value) return
  loadingCluster.value = true
  try {
    const res = await getClusterInfo(activeClusterId.value)
    clusterInfo.value = res.data
  } catch (e: any) {
    // 50302 = 未连接/连接中:离线横幅已表达,不再叠加报错 toast
    if (!((e as ApiError)?.code === 50302)) {
      ElMessage.error(t('clusterBrowse.page.loadClusterInfoFailed', {msg: e.message}))
    }
  } finally {
    loadingCluster.value = false
  }
}

const goTopic = (name: string) => {
  router.push(`/topics/${encodeURIComponent(name)}`)
}

/** 已删除/归档 topic:进入 TopicDetail 并直落归档 tab */
const goArchiveTopic = (name: string) => {
  router.push({path: `/topics/${encodeURIComponent(name)}`, query: {tab: 'archive'}})
}

const refreshTopics = () => {
  topicListRef.value?.loadTopics()
}

onMounted(loadCluster)

// 切换激活集群:整体重取(offline 状态由全局轮询驱动,watch 它可恢复在线后自动补拉)
watch([activeClusterId, offline], () => {
  clusterInfo.value = null
  loadCluster()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
