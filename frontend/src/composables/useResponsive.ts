import {computed, type ComputedRef, ref, type Ref} from 'vue'

/**
 * 桌面窗口三档断点。
 *
 * | 档位   | 视口宽            | 侧栏               | 内容区                     |
 * |--------|-------------------|--------------------|----------------------------|
 * | wide   | ≥ 1440            | 展开 220px         | 卡片 4 列 / 表格全列       |
 * | mid    | 1024–1439         | 默认折叠 64px      | 卡片 2–3 列 / 次要列收起   |
 * | narrow | < 1024            | 覆盖式抽屉         | 卡片 1–2 列 / 横向滚动兜底 |
 *
 * Tauri 窗口最小尺寸 960×640,窄档实际窗口范围 960–1023。
 * 模块级 ref = App 级单例:listener 只注册一次,全组件共享同一份 tier。
 */
export type ResponsiveTier = 'wide' | 'mid' | 'narrow'

const tier = ref<ResponsiveTier>('wide')

/** 窄档快捷判定(视图层 v-if 消费用,免写 tier === 'narrow') */
const isNarrow: ComputedRef<boolean> = computed(() => tier.value === 'narrow')

let initialized = false

const init = () => {
  if (initialized) return
  initialized = true

  const wide = window.matchMedia('(min-width: 1440px)')
  const mid = window.matchMedia('(min-width: 1024px) and (max-width: 1439.98px)')

  const apply = () => {
    tier.value = wide.matches ? 'wide' : mid.matches ? 'mid' : 'narrow'
  }

  if (typeof wide.addEventListener === 'function') {
    wide.addEventListener('change', apply)
    mid.addEventListener('change', apply)
  } else {
    // Safari < 14 兜底:旧 API 已废弃但可用
    wide.addListener(apply)
    mid.addListener(apply)
  }
  // resize 兜底:覆盖不触发 matchMedia change 的边缘场景(如缩放比例变化)
  window.addEventListener('resize', apply)

  apply()
}

/**
 * 读取当前档位。模块级惰性初始化:首次调用注册 listener,
 * 之后任何组件调用都拿到同一份 Ref(与 useGlobalState 同款单例思路,
 * 但纯 UI 状态无需 provide/inject)。
 */
export const useResponsive = (): { tier: Ref<ResponsiveTier>; isNarrow: ComputedRef<boolean> } => {
  init()
  return {tier, isNarrow}
}
