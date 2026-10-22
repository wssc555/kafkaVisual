import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import type {ElectronAPI} from '../../../electron/api-types'

/**
 * 桥接层回归：`initApiBaseUrl` 的两形态（浏览器 / Electron 桌面）。
 *
 * 覆盖桥接契约的三个易漂移点 —— preload 的 channel 名、environment 的 dev 分支、
 * 60s 超时后拉 startupErrors，任一处漂移都会表现为「桌面端白屏 / 一直转圈」。
 */

type MockFn = ReturnType<typeof vi.fn>

type Bridge = {
  isElectron: true
  backendPort: MockFn
  startupErrors: MockFn
  readBackendLog: MockFn
  environment: MockFn
}

const makeBridge = (overrides: Partial<Bridge> = {}): Bridge => ({
  isElectron: true,
  backendPort: vi.fn(async () => 34567),
  startupErrors: vi.fn(async () => []),
  readBackendLog: vi.fn(async () => []),
  environment: vi.fn(async () => ({dev: false, version: '1.0.0'})),
  ...overrides,
})

/** 每个用例重新加载模块，并替换 axios（只关心 defaults.baseURL 是否被改写） */
const loadApi = async () => {
  const defaults = {baseURL: '/api', timeout: 15000}
  vi.doMock('axios', () => ({
    default: {
      create: () => ({
        defaults,
        interceptors: {response: {use: () => {}}},
        get: vi.fn(),
        post: vi.fn(),
        put: vi.fn(),
        patch: vi.fn(),
        delete: vi.fn(),
      }),
    },
  }))
  const mod = await import('../index')
  return {mod, defaults}
}

const installBridge = (bridge: Bridge | undefined) => {
  const holder = globalThis as unknown as {electronAPI?: ElectronAPI}
  if (bridge) holder.electronAPI = bridge as unknown as ElectronAPI
  else delete holder.electronAPI
}

describe('initApiBaseUrl bridge (TC-FE-BRG-*)', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  afterEach(() => {
    installBridge(undefined)
    vi.useRealTimers()
  })

  // TC-FE-BRG-001
  it('TC-FE-BRG-001 浏览器形态：无 electronAPI 时保持 /api 且立即返回', async () => {
    installBridge(undefined)
    const {mod, defaults} = await loadApi()

    const errors = await mod.initApiBaseUrl()

    expect(errors).toEqual([])
    expect(defaults.baseURL).toBe('/api')
  })

  // TC-FE-BRG-002
  it('TC-FE-BRG-002 Electron 开发态：environment.dev=true 时不轮询端口', async () => {
    const bridge = makeBridge({environment: vi.fn(async () => ({dev: true, version: '1.0.0'}))})
    installBridge(bridge)
    const {mod, defaults} = await loadApi()

    const errors = await mod.initApiBaseUrl()

    expect(errors).toEqual([])
    expect(defaults.baseURL).toBe('/api')
    expect(bridge.backendPort).not.toHaveBeenCalled()
  })

  // TC-FE-BRG-003
  it('TC-FE-BRG-003 Electron 打包态：首轮握手即改写 baseURL 指向 sidecar 随机端口', async () => {
    const bridge = makeBridge({backendPort: vi.fn(async () => 41234)})
    installBridge(bridge)
    const {mod, defaults} = await loadApi()

    const errors = await mod.initApiBaseUrl()

    expect(errors).toEqual([])
    expect(defaults.baseURL).toBe('http://127.0.0.1:41234/api')
    expect(bridge.backendPort).toHaveBeenCalledTimes(1)
    // 握手成功不应去拉启动错误
    expect(bridge.startupErrors).not.toHaveBeenCalled()
  })

  // TC-FE-BRG-004
  it('TC-FE-BRG-004 单次握手异常后重试成功（Spring 启动期 invoke 抛错不致命）', async () => {
    const bridge = makeBridge({
      backendPort: vi.fn().mockRejectedValueOnce(new Error('object destroyed')).mockResolvedValue(40000),
    })
    installBridge(bridge)
    const {mod, defaults} = await loadApi()

    const errors = await mod.initApiBaseUrl()

    expect(errors).toEqual([])
    expect(defaults.baseURL).toBe('http://127.0.0.1:40000/api')
    expect(bridge.backendPort).toHaveBeenCalledTimes(2)
  })

  // TC-FE-BRG-005
  it('TC-FE-BRG-005 environment() 探测失败不阻断握手（老壳缺该通道时的兜底）', async () => {
    const bridge = makeBridge({
      environment: vi.fn().mockRejectedValue(new Error('No handler registered')),
      backendPort: vi.fn(async () => 39999),
    })
    installBridge(bridge)
    const {mod, defaults} = await loadApi()

    const errors = await mod.initApiBaseUrl()

    expect(errors).toEqual([])
    expect(defaults.baseURL).toBe('http://127.0.0.1:39999/api')
  })

  // TC-FE-BRG-006
  it('TC-FE-BRG-006 60s 超时：返回壳侧 startupErrors 并保持 /api 兜底', async () => {
    vi.useFakeTimers()
    const bridge = makeBridge({
      backendPort: vi.fn(async () => null),
      startupErrors: vi.fn(async () => ['后端资源缺失: jar=... (存在=false)']),
    })
    installBridge(bridge)
    const {mod, defaults} = await loadApi()

    const pending = mod.initApiBaseUrl()
    await vi.advanceTimersByTimeAsync(61_000)
    const errors = await pending

    expect(errors).toEqual(['后端资源缺失: jar=... (存在=false)'])
    expect(defaults.baseURL).toBe('/api')
    // 120 次轮询（每次 500ms）
    expect(bridge.backendPort).toHaveBeenCalledTimes(120)
  })

  // TC-FE-BRG-007
  it('TC-FE-BRG-007 60s 超时且壳无错误信息时回落到 i18n 通用文案', async () => {
    vi.useFakeTimers()
    const bridge = makeBridge({
      backendPort: vi.fn(async () => null),
      startupErrors: vi.fn(async () => []),
    })
    installBridge(bridge)
    const {mod} = await loadApi()

    const pending = mod.initApiBaseUrl()
    await vi.advanceTimersByTimeAsync(61_000)
    const errors = await pending

    expect(errors).toHaveLength(1)
    expect(errors[0].length).toBeGreaterThan(0)
  })
})
