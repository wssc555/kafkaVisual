import {reactive} from 'vue'

/**
 * 启动诊断共享状态。
 *
 * main.ts 不再阻塞挂载:App 立即 mount,由 App.vue 的 StartupDiagnostics
 * 全屏面板可视化等待期;握手结束(成功或超时)后 markReady 置位,
 * App.vue 才开始真正的启动序列(loadClusters 等,避免 booting 期请求风暴)。
 */
export const bootStatus = reactive({
  /** 握手结束(成功或超时)后为 true,App.vue 据此切换启动面板 → 正常界面 */
  ready: false,
  /** 握手失败时的错误清单(空 = 成功),由 main.ts 弹框展示 */
  errors: [] as string[],
})

/** bootStatus.ready 置位的 Promise 形态:启动序列 await 它即可 */
let resolveReady: () => void = () => {}
export const bootReady = new Promise<void>((resolve) => {
  resolveReady = resolve
})

/** 前端启动事件流(非响应式;StartupDiagnostics 按 500ms 轮询刷新渲染) */
export const bootLogs: string[] = []

/** 记录一条前端启动事件(带 HH:mm:ss.SSS 时间戳) */
export const bootLog = (msg: string) => {
  const ts = new Date().toISOString().slice(11, 23)
  bootLogs.push(`[${ts}] ${msg}`)
}

/** 握手结束:无论成败都置 ready,错误交给弹框与面板展示 */
export const markReady = (errors: string[]) => {
  bootStatus.errors = errors
  bootStatus.ready = true
  resolveReady()
}
