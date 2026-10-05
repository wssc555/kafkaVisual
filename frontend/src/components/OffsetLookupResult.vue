<template>
  <div v-if="result">
    <el-table :data="result.partitions" size="small" stripe max-height="260">
      <el-table-column prop="partition" label="Partition" width="100" />
      <el-table-column :label="t('topicDetail.offsetLookup.hitOffset')" width="140">
        <template #default="{ row }">
          <el-tag v-if="row.offset === -1" size="small" type="info">{{ t('topicDetail.offsetLookup.noMessage') }}</el-tag>
          <span v-else>{{ row.offset }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('topicDetail.offsetLookup.hitTime')" min-width="180">
        <template #default="{ row }">
          <span v-if="row.offset === -1">-</span>
          <span v-else>{{ formatTimestamp(row.matchTimestamp) }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.operation')" align="center" width="120">
        <template #default="{ row }">
          <el-button
            v-if="row.offset !== -1"
            link
            type="primary"
            size="small"
            @click="emit('jump', row.partition, row.offset)"
          >
            {{ t('topicDetail.offsetLookup.jump') }}
          </el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="lookup-summary">
      {{ t('topicDetail.offsetLookup.summary', {time: formatTimestamp(result.timestamp), hit: hitCount, total: result.partitions.length}) }}
    </div>
  </div>
</template>

<script setup lang="ts">
import {computed} from 'vue'
import {useI18n} from 'vue-i18n'
import type {TopicOffsetLookup} from '../api'
import {formatTimestamp} from '../composables/useFormat'

const {t} = useI18n()

const props = defineProps<{
  result: TopicOffsetLookup | null
}>()

const emit = defineEmits<{
  /** 点击「跳转查询」，回传分区与 offset，父组件填入查询表单并触发拉取 */
  jump: [partition: number, offset: number]
}>()

// offset === -1 表示该分区在目标时间之后无消息（后端约定）
const hitCount = computed(
  () => props.result?.partitions.filter((p) => p.offset !== -1).length ?? 0,
)
</script>

<style scoped>
.lookup-summary {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
