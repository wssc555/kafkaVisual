import {computed, type ComputedRef, inject, type InjectionKey, provide, ref, type Ref} from 'vue'
import {
    addFavorite,
    type ClusterDisplayState,
    type ClusterSummary,
    getClusterMode,
    getClusters,
    getClusterStatuses,
    getFavorites,
    getPreferences,
    putPreference,
    removeFavorite,
} from '../api'

export type ClusterModeValue = 'ZOOKEEPER' | 'KRAFT'

/** 收藏集合内的键：`{type}:{name}`，同一集合只存一个集群的收藏 */
const favoriteKey = (type: 'topic' | 'group', name: string) => `${type}:${name}`

export interface GlobalState {
  /** 集群运行模式；**激活集群**的模式，KRaft 模式下隐藏 ZK 浏览器入口 */
  clusterMode: Ref<ClusterModeValue>
  /**
   * 是否已成功拉取到当前激活集群的模式。
   * false 表示仍在请求中，或请求失败（此时必须保持 false 并回退到安全默认，
   * 而不是谎称"已确认"，否则 KRaft 部署 + 一次瞬时失败会永久展示 ZK 浏览器）。
   */
  modeLoaded: Ref<boolean>
  /** 成功路径：设置模式并标记已确认 */
  setClusterMode: (mode: ClusterModeValue) => void
  /**
   * 失败路径：保持 modeLoaded=false，不修改 clusterMode（维持 ZOOKEEPER 回退默认），
   * 并由调用方负责至少提示一次用户（App.vue 的空 catch 是全仓唯一完全无感的失败）。
   */
  fallbackClusterMode: () => void
  /** 按激活集群拉取运行模式；失败走 fallbackClusterMode（调用方负责提示） */
  loadClusterMode: (clusterId: number) => Promise<void>

  /** 当前激活集群 id；null = 尚未选择（启动恢复偏好前 / 零集群） */
  activeClusterId: Ref<number | null>
  /** 全量集群列表（含连接状态与归档配置；来自 GET /api/clusters 全量端点） */
  clusters: Ref<ClusterSummary[]>
  /** 是否已完成首次 loadClusters（无论成败）——零集群引导 / 路由重定向的依据 */
  clustersLoaded: Ref<boolean>
  /** 拉取全量集群列表；失败时抛出由调用方提示，clustersLoaded 仍会置 true */
  loadClusters: () => Promise<void>
  /**
   * 轻量状态轮询：只把 displayState / errorSummary / errorAt 合并进现有列表，
   * 不覆盖配置字段（/clusters/status 是部分摘要，不含归档配置，见 ClusterSummary 注释）。
   */
  refreshStatuses: () => Promise<void>
  /** 切换激活集群：更新 id + 防抖持久化到 ui_preference */
  setActiveCluster: (id: number) => void
  /** 激活集群的摘要对象；列表未含该 id（被删 / 未加载完）时为 null */
  activeCluster: ComputedRef<ClusterSummary | null>

  /** 界面偏好 KV（ui_preference 表的本地镜像） */
  preferences: Ref<Record<string, string>>
  /** 启动时拉取偏好并恢复 activeClusterId（App.vue 启动时序的一部分） */
  loadPreferences: () => Promise<void>
  /** 写偏好：本地立即生效 + 500ms 防抖落 /api/preferences */
  savePreference: (key: string, value: string) => void

  /** 每集群收藏集合：clusterId → {`${type}:${name}`}；未加载过的集群无条目 */
  favoritesByCluster: Ref<Map<number, Set<string>>>
  /** 拉取指定集群收藏并整体替换缓存 */
  loadFavorites: (clusterId: number) => Promise<void>
  /** 星标切换：乐观更新本地集合，失败回滚并由调用方提示 */
  toggleFavorite: (clusterId: number, type: 'topic' | 'group', name: string) => Promise<void>
  isFavorite: (clusterId: number, type: 'topic' | 'group', name: string) => boolean
}

export const GlobalStateKey: InjectionKey<GlobalState> = Symbol('kafka-viz-global-state')

/** 偏好键约定（本期持久化 activeClusterId / asideCollapsed，后续列宽等预留） */
export const PREF_KEYS = {
  activeClusterId: 'activeClusterId',
  asideCollapsed: 'asideCollapsed',
} as const

/** 模块级惰性单例：整个应用只创建一份，跨组件共享 */
let singleton: GlobalState | null = null

/** 偏好写入的防抖定时器（按 key 一把） */
const prefTimers = new Map<string, number>()

const persistPreference = (key: string, value: string) => {
  const existing = prefTimers.get(key)
  if (existing !== undefined) window.clearTimeout(existing)
  prefTimers.set(
    key,
    window.setTimeout(() => {
      prefTimers.delete(key)
      // 写失败不打断交互（后端暂不可达时本地状态仍可用）；下次同键写入会再次尝试
      putPreference(key, value).catch(() => {})
    }, 500),
  )
}

/**
 * 创建（或复用）全局状态并 provide 给后代组件。
 * 仅应在 App.vue 的 setup 中调用一次。
 */
export const useGlobalState = (): GlobalState => {
  if (!singleton) {
    const clusterMode = ref<ClusterModeValue>('ZOOKEEPER')
    const modeLoaded = ref(false)
    const activeClusterId = ref<number | null>(null)
    const clusters = ref<ClusterSummary[]>([])
    const clustersLoaded = ref(false)
    const preferences = ref<Record<string, string>>({})
    const favoritesByCluster = ref<Map<number, Set<string>>>(new Map())

    singleton = {
      clusterMode,
      modeLoaded,
      setClusterMode: (mode: ClusterModeValue) => {
        clusterMode.value = mode
        modeLoaded.value = true
      },
      // 失败时保持 modeLoaded=false：不谎称"已确认"，避免 KRaft + 瞬时失败永久展示 ZK 浏览器
      fallbackClusterMode: () => {
        modeLoaded.value = false
      },
      loadClusterMode: async (clusterId: number) => {
        try {
          const res = await getClusterMode(clusterId)
          singleton!.setClusterMode(res.data.mode)
        } catch (e) {
          singleton!.fallbackClusterMode()
          throw e
        }
      },

      activeClusterId,
      clusters,
      clustersLoaded,
      loadClusters: async () => {
        try {
          const res = await getClusters()
          clusters.value = res.data
        } finally {
          // 成败都算"已尝试"：失败 + 空列表按零集群引导处理，避免无限 loading 语义
          clustersLoaded.value = true
        }
      },
      refreshStatuses: async () => {
        const res = await getClusterStatuses()
        const byId = new Map(res.data.map((s) => [s.id, s]))
        clusters.value = clusters.value.map((c) => {
          const s = byId.get(c.id)
          if (!s) return c
          return {
            ...c,
            displayState: s.displayState as ClusterDisplayState,
            errorSummary: s.errorSummary,
            errorAt: s.errorAt,
          }
        })
      },
      setActiveCluster: (id: number) => {
        if (activeClusterId.value === id) return
        activeClusterId.value = id
        persistPreference(PREF_KEYS.activeClusterId, String(id))
      },
      activeCluster: computed(
        () => clusters.value.find((c) => c.id === activeClusterId.value) || null,
      ),

      preferences,
      loadPreferences: async () => {
        const res = await getPreferences()
        preferences.value = res.data || {}
        // 恢复上次激活的集群（值非法/集群已删时保持 null，由引导流程兜底）
        const saved = Number(preferences.value[PREF_KEYS.activeClusterId])
        if (Number.isInteger(saved) && saved > 0) {
          activeClusterId.value = saved
        }
      },
      savePreference: (key: string, value: string) => {
        preferences.value = {...preferences.value, [key]: value}
        persistPreference(key, value)
      },

      favoritesByCluster,
      loadFavorites: async (clusterId: number) => {
        const res = await getFavorites(clusterId)
        const set = new Set<string>()
        for (const item of res.data || []) {
          set.add(favoriteKey(item.itemType as 'topic' | 'group', item.itemName))
        }
        favoritesByCluster.value = new Map(favoritesByCluster.value).set(clusterId, set)
      },
      toggleFavorite: async (clusterId: number, type: 'topic' | 'group', name: string) => {
        const key = favoriteKey(type, name)
        const current = favoritesByCluster.value.get(clusterId) || new Set<string>()
        const willAdd = !current.has(key)
        // 乐观更新：先改本地，失败回滚
        const next = new Set(current)
        if (willAdd) next.add(key)
        else next.delete(key)
        favoritesByCluster.value = new Map(favoritesByCluster.value).set(clusterId, next)
        try {
          if (willAdd) await addFavorite(clusterId, type, name)
          else await removeFavorite(clusterId, type, name)
        } catch (e) {
          favoritesByCluster.value = new Map(favoritesByCluster.value).set(clusterId, current)
          throw e
        }
      },
      isFavorite: (clusterId: number, type: 'topic' | 'group', name: string) =>
        favoritesByCluster.value.get(clusterId)?.has(favoriteKey(type, name)) || false,
    }
  }

  provide(GlobalStateKey, singleton)
  return singleton
}

/**
 * 读取全局状态。App.vue 尚未 provide 时（如组件被独立挂载）回退到本地默认值，
 * 保证组件不会因缺少 provider 而崩溃。
 */
export const injectGlobalState = (): GlobalState => {
  const injected = inject(GlobalStateKey, null)
  if (injected) return injected

  const fallbackMode = ref<ClusterModeValue>('ZOOKEEPER')
  const fallbackLoaded = ref(false)
  const fallbackClusterId = ref<number | null>(null)
  const fallbackClusters = ref<ClusterSummary[]>([])
  const fallbackPreferences = ref<Record<string, string>>({})
  const noop = () => {}
  const noopAsync = async () => {}
  return {
    clusterMode: fallbackMode,
    modeLoaded: fallbackLoaded,
    setClusterMode: noop,
    fallbackClusterMode: noop,
    loadClusterMode: noopAsync,
    activeClusterId: fallbackClusterId,
    clusters: fallbackClusters,
    clustersLoaded: ref(false),
    loadClusters: noopAsync,
    refreshStatuses: noopAsync,
    setActiveCluster: noop,
    activeCluster: computed(() => null),
    preferences: fallbackPreferences,
    loadPreferences: noopAsync,
    savePreference: noop,
    favoritesByCluster: ref(new Map()),
    loadFavorites: noopAsync,
    toggleFavorite: noopAsync,
    isFavorite: () => false,
  }
}
