<template>
  <div class="headers-editor">
    <div v-for="(item, idx) in items" :key="idx" class="header-row">
      <el-input v-model="item.key" placeholder="header key" style="flex: 1" />
      <span class="header-sep">:</span>
      <el-input v-model="item.value" placeholder="header value" style="flex: 1" />
      <el-button
        type="danger"
        link
        style="margin-left: 8px"
        @click="removeRow(idx)"
      >
        {{ t('topicDetail.headersEditor.remove') }}
      </el-button>
    </div>

    <el-button size="small" @click="addRow">{{ t('topicDetail.headersEditor.add') }}</el-button>
    <div v-if="!items.length" class="header-empty">{{ t('topicDetail.headersEditor.empty') }}</div>
  </div>
</template>

<script setup lang="ts">
import {ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'

const {t} = useI18n()

interface HeaderItem {
  key: string
  value: string
}

const props = defineProps<{
  /** 初始值 { key: value }；父组件传入即回显 */
  modelValue: Record<string, string>
}>()

const emit = defineEmits<{'update:modelValue': [v: Record<string, string>]}>()

const items = ref<HeaderItem[]>([])

/**
 * 行数组 → 对象：丢弃空 key 行（未填完的行不参与提交）。
 * 同时用于「子 → 父」回传与「父 → 子」的内容比较，保证两侧口径一致。
 */
const fold = (arr: HeaderItem[]): Record<string, string> => {
  const obj: Record<string, string> = {}
  for (const item of arr) {
    const k = item.key?.trim()
    if (!k) continue
    obj[k] = item.value ?? ''
  }
  return obj
}

/** 父 → 子：展开成可编辑的行数组 */
watch(
  () => props.modelValue,
  (v) => {
    const incoming = Object.entries(v || {}).map(([key, value]) => ({key, value}))
    // 比较「折叠后的有效内容」而非原始行：
    // 既切断「父更新 → 重建 → emit → 父更新」回环，
    // 又保留用户刚添加、尚未填 key 的空行（空行不参与提交，但需留在界面上）
    if (JSON.stringify(fold(incoming)) === JSON.stringify(fold(items.value))) return
    items.value = incoming
  },
  {immediate: true, deep: true},
)

/** 子 → 父：折叠回对象（丢弃空 key 行） */
const emitChange = () => {
  emit('update:modelValue', fold(items.value))
}

watch(items, emitChange, {deep: true})

const addRow = () => {
  items.value.push({key: '', value: ''})
}

const removeRow = (idx: number) => {
  items.value.splice(idx, 1)
}
</script>

<style scoped>
.headers-editor {
  width: 100%;
}
.header-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.header-sep {
  margin: 0 8px;
  color: var(--el-text-color-secondary);
}
.header-empty {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
}
</style>
