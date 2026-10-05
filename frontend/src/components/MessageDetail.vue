<template>
  <el-drawer v-model="visible" :title="t('topicDetail.messageDetail.title')" size="50%" @update:model-value="(v: any) => emit('update:visible', v)">
    <template v-if="record">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="Topic">{{ record.topic }}</el-descriptions-item>
        <el-descriptions-item label="Partition">{{ record.partition }}</el-descriptions-item>
        <el-descriptions-item label="Offset">{{ record.offset }}</el-descriptions-item>
        <el-descriptions-item label="Timestamp">
          {{ new Date(record.timestamp).toLocaleString() }}
        </el-descriptions-item>
        <el-descriptions-item label="Timestamp Type">{{ record.timestampType }}</el-descriptions-item>
        <el-descriptions-item label="Key">{{ record.key || '(null)' }}</el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">Headers</el-divider>
      <el-table :data="headersList" stripe size="small" v-if="headersList.length">
        <el-table-column prop="key" label="Key" />
        <el-table-column prop="value" label="Value" show-overflow-tooltip />
      </el-table>
      <el-empty v-else :description="t('topicDetail.messageDetail.noHeaders')" />

      <el-divider content-position="left">Value</el-divider>
      <div style="background: var(--el-fill-color-light); border-radius: 4px; padding: 12px; max-height: 500px; overflow: auto">
        <pre style="white-space: pre-wrap; word-break: break-all; font-family: monospace; margin: 0; font-size: 13px">{{ valueText }}</pre>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import {computed} from 'vue'
import {useI18n} from 'vue-i18n'
import type {MessageRecord} from '../api'

const {t} = useI18n()

const props = defineProps<{ visible: boolean; record: MessageRecord | null }>()
const emit = defineEmits<{ 'update:visible': [v: boolean] }>()

const visible = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v),
})

/** 超过该长度的 value 不做本地美化:JSON.parse 会阻塞主线程,避免打开抽屉卡 UI。 */
const MAX_FORMAT_BYTES = 262144

/**
 * 本地按 JSON 美化。列表接口默认不再返回 valueFormatted（后端按需计算），
 * 抽屉展示时在这里付一次格式化成本，而不是让每条消息都背一份美化结果。
 */
const formatJson = (raw: string | null | undefined): string => {
  if (!raw) return '(empty)'
  if (raw.length > MAX_FORMAT_BYTES) return raw
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw        // 非 JSON，原样展示
  }
}

const valueText = computed(() => {
  const r = props.record
  if (!r) return '(empty)'
  // 调用方显式带了格式化结果时优先用（如 includeFormatted=true 的查询）
  return r.valueFormatted || formatJson(r.value)
})

const headersList = computed(() => {
  if (!props.record?.headers) return []
  return Object.entries(props.record.headers).map(([key, value]) => ({ key, value }))
})
</script>