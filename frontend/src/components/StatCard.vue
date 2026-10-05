<template>
  <el-card
    :class="{ 'stat-card--link': !!to }"
    :role="to ? 'link' : undefined"
    class="stat-card"
    shadow="never"
    @click="go"
  >
    <div class="stat-label">{{ label }}</div>
    <div class="stat-value" :class="valueClass">{{ displayValue }}</div>
    <div v-if="hint" class="stat-hint">{{ hint }}</div>
  </el-card>
</template>

<script setup lang="ts">
import {computed} from 'vue'
import {useRouter} from 'vue-router'

const props = defineProps<{
  /** 卡片标题，如「Broker 数」 */
  label: string
  /** 原始数值 */
  value: number | string
  /** 语义色：正常 info / 告警 warning / 危险 danger / 健康 success */
  type?: 'info' | 'success' | 'warning' | 'danger'
  /** 数值下方补充说明 */
  hint?: string
  /** 传入时按字节数格式化（用于 totalLogSizeBytes） */
  bytes?: boolean
  /** 传入时整卡可点击并跳转到该路由（如 /cluster） */
  to?: string
}>()

const router = useRouter()

const go = () => {
  if (props.to) router.push(props.to)
}

const valueClass = computed(() => `stat-value--${props.type || 'info'}`)

const displayValue = computed(() => {
  const v = props.value
  if (v === null || v === undefined) return '-'
  if (props.bytes) {
    const n = Number(v)
    if (Number.isNaN(n)) return String(v)
    if (n < 1024) return n + ' B'
    if (n < 1024 * 1024) return (n / 1024).toFixed(2) + ' KB'
    if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(2) + ' MB'
    if (n < 1024 * 1024 * 1024 * 1024) return (n / 1024 / 1024 / 1024).toFixed(2) + ' GB'
    return (n / 1024 / 1024 / 1024 / 1024).toFixed(2) + ' TB'
  }
  return typeof v === 'number' ? v.toLocaleString() : v
})
</script>

<style scoped>
.stat-card {
  height: 100%;
}
.stat-card--link {
  cursor: pointer;
  transition: border-color 0.2s;
}
.stat-card--link:hover {
  border-color: var(--el-color-primary);
}
.stat-card--link:active .stat-value {
  color: var(--el-color-primary);
}
.stat-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.stat-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
  word-break: break-all;
}
.stat-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
}
.stat-value--info {
  color: var(--el-text-color-primary);
}
.stat-value--success {
  color: var(--el-color-success);
}
.stat-value--warning {
  color: var(--el-color-warning);
}
.stat-value--danger {
  color: var(--el-color-danger);
}
</style>
