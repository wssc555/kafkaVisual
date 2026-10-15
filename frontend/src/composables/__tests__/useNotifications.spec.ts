/**
 * useNotifications（TC-NB-NTS-*）。
 *
 * node 环境、globals:false：describe/it/expect 必须从 vitest 显式 import。
 * 每个用例前 `vi.resetModules()` + `vi.doMock` 后动态 import —— 单例是模块级的，
 * 只有重建模块注册表才能拿到干净实例（照 api/__tests__/index.spec.ts 的隔离方式）。
 *
 * 注意：ApiError 必须与 composable 内部来自同一次模块求值，否则 `e instanceof ApiError`
 * 会因两个不同的类对象而失效 —— 因此 ApiError 同样走动态 import。
 */
import {beforeEach, describe, expect, it, vi} from 'vitest'
import type {AlertRecordItem} from '../../api/inspection'
import {alertToNotification} from '../../notifications/adapters/alert'
import type {NotificationItem} from '../../notifications/types'

type MockFn = ReturnType<typeof vi.fn>
type ApiErrorCtor = new (message: string, code?: number, status?: number) => Error

const alert = (over: Partial<AlertRecordItem> = {}): AlertRecordItem => ({
  id: 7,
  clusterId: 1,
  ruleId: 2,
  severity: 'WARN',
  state: 'FIRING',
  title: 'lag 超阈值',
  ...over,
})

const item = (over: Partial<AlertRecordItem> = {}): NotificationItem => alertToNotification(alert(over))

/** 重建模块 + mock，返回干净的 state 与 mock 句柄 */
const loadState = async () => {
  vi.resetModules()
  vi.doMock('../../api/inspection', () => ({
    getUnreadCount: vi.fn(),
    getAlerts: vi.fn(),
    ackAlert: vi.fn(),
  }))
  const api = (await import('../../api/inspection')) as unknown as {
    getUnreadCount: MockFn
    getAlerts: MockFn
    ackAlert: MockFn
  }
  const apiMod = await import('../../api')
  const {createNotificationState} = await import('../useNotifications')
  return {api, ApiError: apiMod.ApiError as unknown as ApiErrorCtor, state: createNotificationState()}
}

describe('useNotifications（TC-NB-NTS-*）', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
  })

  it('001 refreshUnreadCount 成功 → unreadCount 更新', async () => {
    const {api, state} = await loadState()
    api.getUnreadCount.mockResolvedValue({code: 0, msg: 'ok', data: {count: 5}})

    await state.refreshUnreadCount()

    expect(state.unreadCount.value).toBe(5)
  })

  it('002 refreshUnreadCount 失败 → 静默保持上次值、不 reject', async () => {
    const {api, state} = await loadState()
    state.unreadCount.value = 3
    api.getUnreadCount.mockRejectedValue(new Error('boom'))

    await expect(state.refreshUnreadCount()).resolves.toBeUndefined()
    expect(state.unreadCount.value).toBe(3)
  })

  it('003 序号令牌：慢请求后 resolve，不覆盖后发新值', async () => {
    const {api, state} = await loadState()
    let resolveSlow: (v: unknown) => void = () => {}
    api.getUnreadCount.mockImplementationOnce(
      () => new Promise((r) => { resolveSlow = r }),
    )
    const slow = state.refreshUnreadCount()

    api.getUnreadCount.mockResolvedValueOnce({code: 0, msg: 'ok', data: {count: 7}})
    await state.refreshUnreadCount()
    expect(state.unreadCount.value).toBe(7)

    resolveSlow({code: 0, msg: 'ok', data: {count: 99}})
    await slow
    expect(state.unreadCount.value).toBe(7)
  })

  it('004 loadList 成功 → items 经 adapter 映射、listError=false', async () => {
    const {api, state} = await loadState()
    api.getAlerts.mockResolvedValue({code: 0, msg: 'ok', data: [alert({id: 7}), alert({id: 8})]})

    await state.loadList()

    expect(state.items.value.map((n) => n.id)).toEqual(['alert:7', 'alert:8'])
    expect(state.listError.value).toBe(false)
    expect(state.listLoading.value).toBe(false)
    expect(api.getAlerts).toHaveBeenCalledWith({size: 20})
  })

  it('005 loadList in-flight 去重：loading 中二次调用不发新请求', async () => {
    const {api, state} = await loadState()
    api.getAlerts.mockImplementation(() => new Promise(() => {}))

    const first = state.loadList()
    await state.loadList()

    expect(api.getAlerts).toHaveBeenCalledTimes(1)
    expect(state.listLoading.value).toBe(true)
    // 悬空的 promise 不做断言，避免测试挂起
    void first
  })

  it('006 loadList 失败 → listError=true、items 不清空', async () => {
    const {api, state} = await loadState()
    api.getAlerts.mockResolvedValueOnce({code: 0, msg: 'ok', data: [alert({id: 7})]})
    await state.loadList()

    api.getAlerts.mockRejectedValueOnce(new Error('boom'))
    await state.loadList()

    expect(state.listError.value).toBe(true)
    expect(state.items.value.length).toBe(1)
    expect(state.listLoading.value).toBe(false)
  })

  it('007 ackItem 成功 → read=true、未恢复项 unreadCount-1、ackingIds 清空', async () => {
    const {api, state} = await loadState()
    api.ackAlert.mockResolvedValue({code: 0, msg: 'ok', data: alert()})
    state.unreadCount.value = 3
    const n = item({id: 7})

    await state.ackItem(n)

    expect(api.ackAlert).toHaveBeenCalledWith(7)
    expect(n.read).toBe(true)
    expect(state.unreadCount.value).toBe(2)
    expect(state.ackingIds.value.has('alert:7')).toBe(false)
  })

  it('008 ackItem 遇 40904 → 视为成功（read=true、计数-1、不抛错）', async () => {
    const {api, ApiError, state} = await loadState()
    api.ackAlert.mockRejectedValue(new ApiError('already acked', 40904, 409))
    state.unreadCount.value = 3
    const n = item({id: 7})

    await expect(state.ackItem(n)).resolves.toBeUndefined()
    expect(n.read).toBe(true)
    expect(state.unreadCount.value).toBe(2)
  })

  it('009 ackItem 其它错误 → read 回滚、unreadCount 还原、向调用方抛出', async () => {
    const {api, state} = await loadState()
    api.ackAlert.mockRejectedValue(new Error('boom'))
    state.unreadCount.value = 3
    const n = item({id: 7})

    await expect(state.ackItem(n)).rejects.toThrow('boom')
    expect(n.read).toBe(false)
    expect(state.unreadCount.value).toBe(3)
  })

  it('010 ackItem 对已恢复告警 → read=true 但 unreadCount 不变', async () => {
    const {api, state} = await loadState()
    api.ackAlert.mockResolvedValue({code: 0, msg: 'ok', data: alert()})
    state.unreadCount.value = 3
    const n = item({id: 7, state: 'RECOVERED'})
    expect(n.recovered).toBe(true)

    await state.ackItem(n)

    expect(n.read).toBe(true)
    expect(state.unreadCount.value).toBe(3)
  })

  it('011 ackAll 混合结果 → {ok,fail,total} 正确、结束调用 refreshUnreadCount', async () => {
    const {api, ApiError, state} = await loadState()
    api.ackAlert
      .mockResolvedValueOnce({code: 0, msg: 'ok', data: alert()})
      .mockRejectedValueOnce(new ApiError('already acked', 40904, 409))
      .mockRejectedValueOnce(new Error('boom'))
    api.getUnreadCount.mockResolvedValue({code: 0, msg: 'ok', data: {count: 1}})
    state.items.value = [item({id: 1}), item({id: 2}), item({id: 3})]

    const r = await state.ackAll()

    expect(r).toEqual({ok: 2, fail: 1, total: 3})
    expect(api.getUnreadCount).toHaveBeenCalledTimes(1)
    expect(state.ackingAll.value).toBe(false)
    // 真失败的那条回滚为未读
    expect(state.items.value.filter((n) => !n.read).map((n) => n.id)).toEqual(['alert:3'])
  })

  it('012 ackAll 全已读时直接返回零值、不调 ackAlert', async () => {
    const {api, state} = await loadState()
    const read = item({id: 1})
    read.read = true
    state.items.value = [read]

    const r = await state.ackAll()

    expect(r).toEqual({ok: 0, fail: 0, total: 0})
    expect(api.ackAlert).not.toHaveBeenCalled()
    expect(api.getUnreadCount).not.toHaveBeenCalled()
  })
})
