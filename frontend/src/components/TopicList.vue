<template>
  <el-card shadow="never">
    <template #header>
      <div style="display: flex; justify-content: space-between; align-items: center">
        <span style="font-weight: 600">{{ t('clusterBrowse.topicList.title') }}</span>
        <div class="header-actions">
          <el-button :loading="loading" size="small" @click="loadTopics">{{ t('common.refresh') }}</el-button>
          <el-button :disabled="offlineMode" size="small" type="primary" @click="$emit('create')">
            {{ t('clusterBrowse.topicList.newTopic') }}
          </el-button>
        </div>
      </div>
    </template>

    <!-- 离线横幅:Kafka 列表不可得,回退展示归档台账(离线可查历史) -->
    <el-alert
      v-if="offlineMode"
      :closable="false"
      :description="t('clusterBrowse.topicList.offlineAlertDesc')"
      show-icon
      style="margin-bottom: 12px"
      :title="t('clusterBrowse.topicList.offlineAlertTitle')"
      type="warning"
    />

    <el-table
      v-loading="loading"
      :data="rows"
      stripe
      style="width: 100%"
      @row-click="onRowClick"
    >
      <!-- 星标列:收藏持久化到该集群,独立于 Kafka 数据 -->
      <el-table-column align="center" label="" width="44">
        <template #default="{ row }">
          <el-icon
            :class="{ 'is-active': isActive(row) }"
            class="star-icon"
            @click.stop="toggleStar(row)"
          >
            <StarFilled v-if="isActive(row)" />
            <Star v-else />
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column :label="t('clusterBrowse.topicList.topicName')" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="{ 'deleted-name': row.deleted }">{{ row.name }}</span>
          <el-tag v-if="row.deleted" size="small" style="margin-left: 8px" type="info">
            {{ t('clusterBrowse.topicList.deletedTag') }}
          </el-tag>
          <el-tag v-else-if="row.archived" size="small" style="margin-left: 8px" type="warning">
            {{ t('clusterBrowse.topicList.archivedTag') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.operation')" align="center" fixed="right" width="100">
        <template #default="{ row }">
          <!-- 已删除 topic 无法对 Kafka 执行删除(它已经不在了),只保留查看历史 -->
          <el-button
            v-if="row.deleted"
            size="small"
            @click="$emit('view-archive', row.name)"
          >
            {{ t('clusterBrowse.topicList.viewHistory') }}
          </el-button>
          <el-button v-else :disabled="offlineMode" size="small" type="danger" @click.stop="handleDelete(row.name)">
            {{ t('common.remove') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import {onMounted, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {Star, StarFilled} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {type ArchiveTopicSummary, deleteTopic, getArchiveTopics, getTopics} from '../api'
import {injectGlobalState} from '../composables/useGlobalState'
import {useCrudConfirm} from '../composables/useCrudConfirm'

const {t} = useI18n()

/**
 * 行模型:Kafka 实时列表(string[])与归档台账(getArchiveTopics)的并集。
 * - deleted=true:已从 Kafka 消失,仅归档可查(来自 topic_registry.deleted=1);
 * - archived=true:离线回退模式下,无法核实该 topic 是否仍在 Kafka,标注"归档"。
 */
interface TopicRow {
  name: string
  deleted: boolean
  archived?: boolean
}

const emit = defineEmits<{ create: []; view: [name: string]; 'view-archive': [name: string] }>()

const {confirm} = useCrudConfirm()
const {activeClusterId, loadFavorites, toggleFavorite, isFavorite} = injectGlobalState()

const rows = ref<TopicRow[]>([])
const loading = ref(false)
const offlineMode = ref(false)

const isActive = (row: TopicRow) =>
  activeClusterId.value != null && isFavorite(activeClusterId.value, 'topic', row.name)

const toggleStar = async (row: TopicRow) => {
  if (activeClusterId.value == null) return
  try {
    await toggleFavorite(activeClusterId.value, 'topic', row.name)
  } catch (e: any) {
    ElMessage.error(t('clusterBrowse.topicList.favoriteFailed', {msg: e.message}))
  }
}

const onRowClick = (row: TopicRow) => {
  if (row.deleted) {
    // 已删除 topic 只能看历史:直接进归档 tab(父组件跳转带 ?tab=archive)
    emit('view-archive', row.name)
  } else {
    emit('view', row.name)
  }
}

const loadTopics = async () => {
  const cid = activeClusterId.value
  if (cid == null) {
    rows.value = []
    offlineMode.value = false
    return
  }
  loading.value = true
  try {
    const res = await getTopics(cid)
    const names = res.data
    // 已删除 topic 并集:deleted=1 且不在 Kafka 列表里的归档条目
    let deletedArchive: ArchiveTopicSummary[] = []
    try {
      const archiveRes = await getArchiveTopics(cid)
      deletedArchive = (archiveRes.data || []).filter(
        (t) => t.deleted === 1 && !names.includes(t.topicName),
      )
    } catch {
      // 归档台账失败不影响实时列表主流程
    }
    rows.value = [
      ...names.map((n) => ({name: n, deleted: false})),
      ...deletedArchive.map((t) => ({name: t.topicName, deleted: true})),
    ]
    offlineMode.value = false
  } catch (e: any) {
    // 离线 / 未连接(50302 等):回退到归档台账 —— 这正是"离线集群仍可查历史"的入口
    try {
      const archiveRes = await getArchiveTopics(cid)
      rows.value = (archiveRes.data || []).map((t) => ({
        name: t.topicName,
        deleted: t.deleted === 1,
        archived: true,
      }))
      offlineMode.value = true
      ElMessage.info(t('clusterBrowse.topicList.switchedToArchiveMode'))
    } catch (e2: any) {
      rows.value = []
      offlineMode.value = false
      ElMessage.error(
        e2
          ? t('clusterBrowse.topicList.loadTopicsFailedWithArchive', {msg: e.message, msg2: e2.message})
          : t('clusterBrowse.topicList.loadTopicsFailed', {msg: e.message}),
      )
    }
  } finally {
    loading.value = false
  }
}

const handleDelete = async (name: string) => {
  if (activeClusterId.value == null) return
  // 不可逆写操作：经 useCrudConfirm 封装确认（type='error'），避免裸调 ElMessageBox
  const ok = await confirm(
    t('clusterBrowse.topicList.confirmDeleteTopic', {name}),
    t('common.confirmDeleteTitle'),
    'error',
  )
  if (!ok) return
  try {
    await deleteTopic(activeClusterId.value, name)
    ElMessage.success(t('clusterBrowse.topicList.topicDeleted', {name}))
    await loadTopics()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  // 收藏列表独立加载(失败不阻塞 topic 列表)
  if (activeClusterId.value != null) {
    loadFavorites(activeClusterId.value).catch(() => {})
  }
  await loadTopics()
})

/**
 * 同组件复用:切换激活集群后重置并重取。
 * 离线模式随新集群的状态在 loadTopics 内重算。
 */
watch(activeClusterId, async (cid) => {
  offlineMode.value = false
  rows.value = []
  if (cid != null) {
    loadFavorites(cid).catch(() => {})
  }
  await loadTopics()
})

defineExpose({ loadTopics })
</script>

<style scoped>
/* 行点击可进入详情，鼠标悬停显示手型 */
:deep(.el-table__row) {
  cursor: pointer;
}
.header-actions {
  display: flex;
  gap: 8px;
}
.deleted-name {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}
.star-icon {
  cursor: pointer;
  color: var(--el-text-color-placeholder);
  font-size: 16px;
  transition: color 0.2s;
}
.star-icon:hover {
  color: var(--el-color-warning);
}
.star-icon.is-active {
  color: var(--el-color-warning);
}
</style>
