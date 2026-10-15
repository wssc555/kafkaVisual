/**
 * 站内通知中心的状态与动作。
 *
 * 架构与 `useGlobalState` 同构（模块级单例 + provide/inject）：
 * - `createNotificationState()` 是可测纯工厂（无 provide），node 环境直接单测；
 * - `useNotifications()` 仅由 App.vue 调用一次（provide）；
 * - `injectNotifications()` 供组件消费，无 provider 时回退惰性默认（独立挂载不崩）。
 *
 * 纪律：
 * - 本层不 import element-plus / 不碰 DOM（保 node 可测），汇总提示由组件层发；
 * - 已读 = 后端 ack；40904（已确认）在前端视为成功；
 * - 未读口径是后端的（FIRING 且未 ack 且未 recovered），因此 ack 已恢复但未 ack 的告警
 *   不应在前端自减计数（后端 unread-count 本就没把它算进去）。
 */
import {inject, type InjectionKey, provide, ref, type Ref} from 'vue'
import {ApiError} from '../api'
import {ackAlert, getAlerts, getUnreadCount} from '../api/inspection'
import {alertIdOf, alertToNotification} from '../notifications/adapters/alert'
import type {NotificationItem} from '../notifications/types'

/** 面板一次拉取的条数（后端排序固定 fired_at DESC, id DESC） */
const LIST_SIZE = 20

/** ackAll 的并发上限：20 条串行太慢，40904 在并发下是预期结果 */
const ACK_CONCURRENCY = 5

/** 前端视为「成功」的业务码：告警已确认（重复 ack） */
const CODE_ALREADY_ACKED = 40904

export interface NotificationsState {
  unreadCount: Ref<number>
  items: Ref<NotificationItem[]>
  listLoading: Ref<boolean>
  /** 列表加载失败标记；true 时 items 保持上次值不清空，组件展示重试 */
  listError: Ref<boolean>
  /** 正在 ack 的通知 id 集合（UI 禁用对应按钮） */
  ackingIds: Ref<Set<string>>
  ackingAll: Ref<boolean>

  /** 拉未读数；失败静默保持上次值（巡检未启用/后端不可达，沿用 App.vue 现有纪律） */
  refreshUnreadCount: () => Promise<void>
  /** 拉最近 20 条（不带过滤；排序后端固定 fired_at DESC, id DESC） */
  loadList: () => Promise<void>
  /** 单条标记已读；40904 视为成功，其它错误回滚后向调用方抛出 */
  ackItem: (n: NotificationItem) => Promise<void>
  /** 批量标记已读，返回汇总（含 40904 计 ok）；汇总提示由组件层发，composable 不弹 UI */
  ackAll: () => Promise<{ok: number; fail: number; total: number}>
}

export const NotificationsKey: InjectionKey<NotificationsState> = Symbol('kafka-viz-notifications')

/** 模块级惰性单例：整个应用只创建一份，跨组件共享 */
let singleton: NotificationsState | null = null
/** 无 provider 时的兜底实例（组件被独立挂载场景） */
let fallback: NotificationsState | null = null

/** 纯工厂：无 provide，node 环境可直接单测 */
export const createNotificationState = (): NotificationsState => {
  const unreadCount = ref(0)
  const items = ref<NotificationItem[]>([])
  const listLoading = ref(false)
  const listError = ref(false)
  const ackingIds = ref<Set<string>>(new Set())
  const ackingAll = ref(false)

  /** 序号令牌：旧响应不得覆盖后发请求的结果 */
  let unreadReqId = 0
  let listReqId = 0

  /**
   * 乐观标记已读并调用后端 ack。
   * 成功（含 40904）返回 true；真实失败回滚本地状态并抛出，由调用方决定提示方式。
   */
  const applyAck = async (n: NotificationItem): Promise<void> => {
    n.read = true
    // 已恢复但未 ack 的告警不计入后端未读口径，ack 它不应自减计数
    const decrement = !n.recovered
    if (decrement) unreadCount.value = Math.max(0, unreadCount.value - 1)
    try {
      // 多来源扩展后 raw.id 可能不是 number：无法 ack 的项不再发请求，乐观已读即终态
      const alertId = alertIdOf(n)
      if (alertId === null) return
      await ackAlert(alertId)
    } catch (e) {
      if (e instanceof ApiError && e.code === CODE_ALREADY_ACKED) return
      n.read = false
      if (decrement) unreadCount.value += 1
      throw e
    }
  }

  const refreshUnreadCount = async (): Promise<void> => {
    const req = ++unreadReqId
    try {
      const count = (await getUnreadCount()).data?.count ?? 0
      if (req === unreadReqId) unreadCount.value = count
    } catch {
      // 巡检未启用 / 后端暂不可达：静默保持上次值，不叠加报错
    }
  }

  const loadList = async (): Promise<void> => {
    if (listLoading.value) return
    const req = ++listReqId
    listLoading.value = true
    try {
      const res = await getAlerts({size: LIST_SIZE})
      if (req === listReqId) {
        items.value = (res.data || []).map(alertToNotification)
        listError.value = false
      }
    } catch {
      // items 不清空：保留上次内容 + 错误态重试
      if (req === listReqId) listError.value = true
    } finally {
      if (req === listReqId) listLoading.value = false
    }
  }

  const ackItem = async (n: NotificationItem): Promise<void> => {
    if (n.read || ackingIds.value.has(n.id)) return
    // ref<Set> 直接 .add 不触发更新，必须整体赋新 Set
    ackingIds.value = new Set(ackingIds.value).add(n.id)
    try {
      await applyAck(n)
    } finally {
      const next = new Set(ackingIds.value)
      next.delete(n.id)
      ackingIds.value = next
    }
  }

  const ackAll = async (): Promise<{ok: number; fail: number; total: number}> => {
    const targets = items.value.filter((n) => !n.read)
    const total = targets.length
    if (total === 0) return {ok: 0, fail: 0, total: 0}

    ackingAll.value = true
    // 批量期间占住目标 id 的单条按钮，避免与 ackAll 双重 ack
    const occupied = new Set(ackingIds.value)
    targets.forEach((n) => occupied.add(n.id))
    ackingIds.value = occupied
    let ok = 0
    let fail = 0
    try {
      for (let i = 0; i < targets.length; i += ACK_CONCURRENCY) {
        const batch = targets.slice(i, i + ACK_CONCURRENCY)
        const results = await Promise.allSettled(batch.map((n) => applyAck(n)))
        for (const r of results) {
          if (r.status === 'fulfilled') ok++
          else fail++
        }
      }
    } finally {
      const next = new Set(ackingIds.value)
      targets.forEach((n) => next.delete(n.id))
      ackingIds.value = next
      ackingAll.value = false
    }
    // 拉权威值，自愈轮询间隔期内的计数漂移
    await refreshUnreadCount()
    return {ok, fail, total}
  }

  return {
    unreadCount,
    items,
    listLoading,
    listError,
    ackingIds,
    ackingAll,
    refreshUnreadCount,
    loadList,
    ackItem,
    ackAll,
  }
}

/** 单例 + provide，仅 App.vue setup 调用一次 */
export const useNotifications = (): NotificationsState => {
  if (!singleton) singleton = createNotificationState()
  provide(NotificationsKey, singleton)
  return singleton
}

/** 组件消费；无 provider 时回退惰性默认值（照 injectGlobalState，保独立挂载不崩） */
export const injectNotifications = (): NotificationsState => {
  const injected = inject(NotificationsKey, null)
  if (injected) return injected
  if (!fallback) fallback = createNotificationState()
  return fallback
}
