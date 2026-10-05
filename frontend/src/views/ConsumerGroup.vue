<template>
  <div>
    <!-- 查询区 -->
    <el-card shadow="never" style="margin-bottom: 20px">
      <template #header><span style="font-weight: 600">{{ t('groupsMeta.groups.title') }}</span></template>

      <el-form :inline="true" :model="form">
        <el-form-item :label="t('groupsMeta.groups.groupLabel')">
          <el-select
            v-model="form.group"
            filterable
            allow-create
            clearable
            :placeholder="t('groupsMeta.groups.groupPlaceholder')"
            style="width: 250px"
          >
            <el-option v-for="g in groups" :key="g" :label="g" :value="g" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="loadingOverview"
            :disabled="!form.group"
            @click="loadOverview"
          >
            {{ t('groupsMeta.groups.queryAll') }}
          </el-button>
        </el-form-item>
      </el-form>

      <el-divider content-position="left">{{ t('groupsMeta.groups.singleDivider') }}</el-divider>

      <el-form :inline="true" :model="form">
        <el-form-item label="Topic">
          <el-input
            v-model="form.topic"
            :placeholder="t('groupsMeta.groups.topicPlaceholder')"
            style="width: 250px"
            @keyup.enter="searchSingle"
          />
        </el-form-item>
        <el-form-item>
          <el-button :loading="loadingSingle" :disabled="!form.group || !form.topic" @click="searchSingle">
            {{ t('groupsMeta.groups.quickQuery') }}
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 全组概览 -->
    <el-card v-if="overview" shadow="never" style="margin-bottom: 20px">
      <template #header>
        <div class="card-header">
          <span class="group-title" style="font-weight: 600">
            <!-- 组星标:收藏按 (clusterId, 'group', group) 持久化 -->
            <el-icon :class="{ 'is-active': isGroupStarred }" class="star-icon" @click="toggleGroupStar">
              <StarFilled v-if="isGroupStarred" />
              <Star v-else />
            </el-icon>
            {{ overview.group }}
          </span>
          <div>
            <el-tag type="info" size="small" style="margin-right: 8px">
              State: {{ overview.state }}
            </el-tag>
            <el-tag type="info" size="small" style="margin-right: 8px">
              {{ t('groupsMeta.groups.members', {n: overview.memberCount}) }}
            </el-tag>
            <el-tag :type="overview.totalLag > 0 ? 'warning' : 'success'" size="small">
              Total Lag: {{ formatNumber(overview.totalLag) }}
            </el-tag>
          </div>
        </div>
      </template>

      <!-- 组操作：仅 Empty 状态可用 -->
      <div class="group-actions">
        <el-tooltip
          :disabled="isGroupEmpty"
          :content="t('groupsMeta.groups.resetOffsetDisabledHint')"
          placement="top"
        >
          <span>
            <el-button type="warning" size="small" :disabled="!isGroupEmpty" @click="showReset = true">
              {{ t('groupsMeta.groups.resetOffsetBtn') }}
            </el-button>
          </span>
        </el-tooltip>
        <el-tooltip
          :disabled="isGroupEmpty"
          :content="t('groupsMeta.groups.deleteGroupDisabledHint')"
          placement="top"
        >
          <span>
            <el-button type="danger" size="small" :disabled="!isGroupEmpty" @click="handleDeleteGroup">
              {{ t('groupsMeta.groups.deleteGroupBtn') }}
            </el-button>
          </span>
        </el-tooltip>
        <el-button :loading="loadingOverview" size="small" @click="loadOverview">{{ t('common.refresh') }}</el-button>
        <span v-if="!isGroupEmpty" class="group-hint">{{ t('groupsMeta.groups.groupNotEmptyHint') }}</span>
      </div>

      <el-tabs v-model="activeTopicTab" style="margin-top: 12px">
        <el-tab-pane
          v-for="tp in overview.topics"
          :key="tp.topic"
          :label="`${tp.topic} (${tp.totalLag})`"
          :name="tp.topic"
        >
          <PartitionProgressTable :partitions="tp.partitions" />
        </el-tab-pane>
      </el-tabs>

      <el-empty v-if="!overview.topics?.length" :description="t('groupsMeta.groups.noOffsetsGroup')" />
    </el-card>

    <!-- 单 Topic 快查结果 -->
    <el-card v-if="single" shadow="never" style="margin-bottom: 20px">
      <template #header>
        <div class="card-header">
          <span style="font-weight: 600">{{ single.group }} / {{ single.topic }}</span>
          <div>
            <el-tag type="info" size="small" style="margin-right: 8px">
              State: {{ single.state }}
            </el-tag>
            <el-tag :type="single.totalLag > 0 ? 'warning' : 'success'" size="small">
              Total Lag: {{ formatNumber(single.totalLag) }}
            </el-tag>
          </div>
        </div>
      </template>
      <PartitionProgressTable :partitions="single.partitions" />
      <el-empty v-if="!single.partitions?.length" :description="t('groupsMeta.groups.noOffsetsTopic')" />
    </el-card>

    <ResetOffsetsDialog
      v-model:visible="showReset"
      :cluster-id="activeClusterId ?? 0"
      :group="form.group"
      :available-partitions="availablePartitions"
      @updated="loadOverview"
    />
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {Star, StarFilled} from '@element-plus/icons-vue'
import {
  type ConsumerGroupDetail,
  type ConsumerGroupOverview,
  deleteConsumerGroup,
  describeConsumerGroup,
  getConsumerGroupOverview,
  listConsumerGroups,
} from '../api'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import {useFormat} from '../composables/useFormat'
import {injectGlobalState} from '../composables/useGlobalState'
import {ElMessage} from 'element-plus'
import ResetOffsetsDialog from '../components/ResetOffsetsDialog.vue'
import PartitionProgressTable from '../components/PartitionProgressTable.vue'

const {confirm} = useCrudConfirm()
const {formatNumber} = useFormat()
const {t} = useI18n()
const {activeClusterId, loadFavorites, toggleFavorite, isFavorite} = injectGlobalState()

const groups = ref<string[]>([])
const overview = ref<ConsumerGroupOverview | null>(null)
const single = ref<ConsumerGroupDetail | null>(null)
const loadingOverview = ref(false)
const loadingSingle = ref(false)
const activeTopicTab = ref('')
const showReset = ref(false)

const form = ref({group: '', topic: ''})

/** 仅 Empty 状态允许重置 / 删除，对应后端 40902 */
const isGroupEmpty = computed(() => overview.value?.state === 'Empty')

/** 组星标:针对当前加载的 overview.group */
const isGroupStarred = computed(
  () =>
    activeClusterId.value != null &&
    !!overview.value &&
    isFavorite(activeClusterId.value, 'group', overview.value.group),
)

const toggleGroupStar = async () => {
  if (activeClusterId.value == null || !overview.value) return
  try {
    await toggleFavorite(activeClusterId.value, 'group', overview.value.group)
  } catch (e: any) {
    ElMessage.error(t('groupsMeta.groups.favoriteFailed', {msg: e.message}))
  }
}

/** 供重置对话框下拉提示的分区号（取当前 tab 所属 topic） */
const availablePartitions = computed(() => {
  if (!overview.value) return []
  // topics 恒存在（ConsumerGroupOverview 无 @JsonInclude(NON_NULL)，无提交时为 []），
  // 可选链只是对残缺响应的防御，不表示后端会省略该字段
  const tp = overview.value.topics?.find((t) => t.topic === activeTopicTab.value)
  return (tp?.partitions || []).map((p) => p.partition)
})

const loadGroups = async () => {
  if (activeClusterId.value == null) {
    groups.value = []
    return
  }
  try {
    const res = await listConsumerGroups(activeClusterId.value)
    groups.value = res.data
  } catch {
    // 组列表是辅助数据，失败时静默，避免干扰主流程（离线集群此处必然失败）
  }
}

const loadOverview = async () => {
  if (!form.value.group) return
  if (activeClusterId.value == null) {
    ElMessage.warning(t('groupsMeta.groups.selectClusterFirst'))
    return
  }
  loadingOverview.value = true
  overview.value = null
  single.value = null
  try {
    const res = await getConsumerGroupOverview(activeClusterId.value, form.value.group)
    overview.value = res.data
    // 默认展开第一个 topic
    if (res.data.topics?.length > 0) {
      activeTopicTab.value = res.data.topics[0].topic
    }
  } catch (e: any) {
    ElMessage.error(e.message)
    // 组不存在（40402）时同步刷新下拉，避免残留已删除的组
    loadGroups()
  } finally {
    loadingOverview.value = false
  }
}

const searchSingle = async () => {
  if (!form.value.group || !form.value.topic) {
    ElMessage.warning(t('groupsMeta.groups.inputGroupAndTopic'))
    return
  }
  if (activeClusterId.value == null) {
    ElMessage.warning(t('groupsMeta.groups.selectClusterFirst'))
    return
  }
  loadingSingle.value = true
  single.value = null
  try {
    const res = await describeConsumerGroup(activeClusterId.value, form.value.group, form.value.topic)
    single.value = res.data
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    loadingSingle.value = false
  }
}

const handleDeleteGroup = async () => {
  if (activeClusterId.value == null) return
  const group = form.value.group
  const ok = await confirm(
    t('groupsMeta.groups.confirmDeleteMsg', {group}),
    t('groupsMeta.groups.confirmDeleteTitle'),
    'error',
  )
  if (!ok) return

  try {
    await deleteConsumerGroup(activeClusterId.value, group)
    ElMessage.success(t('groupsMeta.groups.deleted', {group}))
    overview.value = null
    single.value = null
    form.value.group = ''
    await loadGroups()
  } catch (e: any) {
    // 40902 组非 Empty / 40402 组不存在
    ElMessage.error(e.message)
    await loadOverview()
  }
}

onMounted(loadGroups)

// 切换激活集群:重置查询状态并重取组列表
watch(activeClusterId, () => {
  groups.value = []
  overview.value = null
  single.value = null
  form.value = {group: '', topic: ''}
  loadGroups()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.group-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.star-icon {
  cursor: pointer;
  color: var(--el-text-color-placeholder);
  font-size: 18px;
  transition: color 0.2s;
}
.star-icon:hover,
.star-icon.is-active {
  color: var(--el-color-warning);
}
.group-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.group-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
