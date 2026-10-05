<template>
  <!--
    启动诊断面板:握手等待期(bootStatus.ready=false)的全屏遮罩。
    上区 = 后端日志(每 500ms 拉取 backend.log 尾部,自动滚到底);下区 = 前端启动事件流。
    握手结束(成功或超时)后由父组件卸载本组件,轮询随之停止。
  -->
  <div class="boot-mask">
    <div class="boot-panel">
      <div class="boot-head">
        <span class="boot-title">{{ t('app.boot.title') }}</span>
        <span class="boot-elapsed">{{ elapsed }}s</span>
      </div>

      <div class="boot-section">{{ t('app.boot.backendSection') }}</div>
      <pre ref="backendPre" class="boot-log">{{ backendLines.join('\n') || t('app.boot.waitingJava') }}</pre>

      <div class="boot-section">{{ t('app.boot.frontendSection') }}</div>
      <pre class="boot-log boot-log--front">{{ bootLogs.join('\n') || t('app.boot.initializing') }}</pre>

      <div class="boot-tip">
        {{ t('app.boot.fullLog') }}
        <span v-if="tailTruncated">{{ t('app.boot.tailOnly', {n: TAIL_LINES}) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {nextTick, onBeforeUnmount, onMounted, ref} from 'vue'
import {useI18n} from 'vue-i18n'
import {bootLogs} from '../composables/bootLog'

const {t} = useI18n()

const TAIL_LINES = 500
const backendLines = ref<string[]>([])
const tailTruncated = ref(false)
const backendPre = ref<HTMLPreElement | null>(null)
const elapsed = ref(0)

const startedAt = Date.now()
let pollTimer: number | null = null
let clockTimer: number | null = null

const refreshBackend = async () => {
  // 浏览器/开发模式无 __TAURI_INTERNALS__,面板根本不会渲染(ready 恒 true),此处双保险
  const tauri = (globalThis as unknown as {__TAURI_INTERNALS__?: {invoke?: (cmd: string) => Promise<unknown>}}).__TAURI_INTERNALS__
  if (!tauri || typeof tauri.invoke !== 'function') return
  try {
    const raw = await tauri.invoke('read_backend_log')
    if (Array.isArray(raw)) {
      const lines = raw.map(String)
      tailTruncated.value = lines.length >= TAIL_LINES
      backendLines.value = lines
      // 日志增长时跟随滚动到底部;用户手动上滚则不打扰
      await nextTick()
      const pre = backendPre.value
      if (pre) pre.scrollTop = pre.scrollHeight
    }
  } catch {
    // 拉取失败(命令未注册的旧壳)静默,下一轮再试
  }
}

onMounted(() => {
  pollTimer = window.setInterval(refreshBackend, 500)
  clockTimer = window.setInterval(() => {
    elapsed.value = Math.floor((Date.now() - startedAt) / 1000)
  }, 1000)
  void refreshBackend()
})

onBeforeUnmount(() => {
  if (pollTimer !== null) clearInterval(pollTimer)
  if (clockTimer !== null) clearInterval(clockTimer)
})
</script>

<style scoped>
.boot-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
}
.boot-panel {
  width: min(860px, 94vw);
  max-height: 88vh;
  overflow: auto;
  background: var(--el-bg-color, #fff);
  border-radius: 12px;
  padding: 16px 20px;
  box-shadow: var(--el-box-shadow-light);
}
.boot-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.boot-title {
  font-size: 15px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}
.boot-elapsed {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.boot-section {
  margin: 10px 0 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.boot-log {
  margin: 0;
  padding: 10px;
  height: 220px;
  overflow: auto;
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
.boot-log--front {
  height: 130px;
  background: #f5f7fa;
  color: var(--el-text-color-regular);
}
.boot-tip {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
</style>
