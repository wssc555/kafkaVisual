// @vitest-environment jsdom
// ZkBrowser 依赖真实 DOM 渲染（el-card / el-empty / el-alert / el-table）。
import '../../test/jsdomPolyfill'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {flushPromises, mount, type VueWrapper} from '@vue/test-utils'
import {nextTick, ref, type Ref} from 'vue'
import ElementPlus, {ElAlert} from 'element-plus'
import i18n from '../../i18n'
import {listZkChildren, type ZkNodeStat} from '../../api'
import {type ClusterModeValue, GlobalStateKey} from '../../composables/useGlobalState'
import ZkBrowser from '../ZkBrowser.vue'

// ---- 依赖替身 ----
vi.mock('../../api', () => ({
  listZkChildren: vi.fn(),
  deleteZkNode: vi.fn(),
}))

/**
 * 全局状态必须在**每个用例里重建**：组件的 watcher 挂在这些 ref 上，
 * 而 @vue/test-utils 不会自动卸载上一个用例挂载的实例——若共享同一份 ref，
 * 用例里翻转 modeLoaded 会让所有旧实例的 watcher 一起触发、重复发请求
 * （这正是「调用了 2/3 次而期望 1 次」的根因）。
 */
let clusterMode: Ref<ClusterModeValue>
let modeLoaded: Ref<boolean>
let activeWrapper: VueWrapper<any> | null = null

const mountBrowser = (): VueWrapper<any> => {
  activeWrapper = mount(ZkBrowser, {
    global: {
      plugins: [ElementPlus, i18n],
      provide: {
        [GlobalStateKey]: {
          clusterMode,
          modeLoaded,
          setClusterMode: vi.fn(),
          fallbackClusterMode: vi.fn(),
          // loadChildren / watch(activeClusterId) 直接读 .value：
          // provide 必须完整，否则 activeClusterId 为 undefined → 挂载即 TypeError
          activeClusterId: ref(1),
        },
      },
    },
  })
  return activeWrapper
}

const alertTitle = (wrapper: VueWrapper<any>): string | undefined => {
  const alerts = wrapper.findAllComponents(ElAlert)
  return alerts.length === 1 ? String(alerts[0].props('title')) : undefined
}

beforeEach(() => {
  vi.mocked(listZkChildren).mockReset()
  clusterMode = ref<ClusterModeValue>('ZOOKEEPER')
  modeLoaded = ref(false)
})

afterEach(() => {
  activeWrapper?.unmount()
  activeWrapper = null
})

describe('ZkBrowser 模式自检（F11：KRaft 下不发必然 404 的 /api/zk/**）', () => {
  it('模式已确认且为 KRaft：不发请求，展示跳转引导', async () => {
    clusterMode.value = 'KRAFT'
    modeLoaded.value = true

    const wrapper = mountBrowser()
    await flushPromises()

    expect(vi.mocked(listZkChildren)).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('KRaft 模式')
    expect(wrapper.text()).toContain('前往元数据浏览器')
    // ZK 浏览表单整体不渲染
    expect(wrapper.text()).not.toContain('常用路径')
  })

  it('模式已确认且为 ZooKeeper：按默认路径立即查询', async () => {
    clusterMode.value = 'ZOOKEEPER'
    modeLoaded.value = true
    vi.mocked(listZkChildren).mockResolvedValue({code: 0, msg: 'ok', data: {path: '/brokers', children: []}})

    const wrapper = mountBrowser()
    await flushPromises()

    expect(vi.mocked(listZkChildren)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(listZkChildren)).toHaveBeenCalledWith(1, '/brokers', false)
    expect(wrapper.text()).not.toContain('KRaft 模式')
  })

  it('首帧 mode 未确认：不自动请求；确认后（ZooKeeper）再发起', async () => {
    vi.mocked(listZkChildren).mockResolvedValue({code: 0, msg: 'ok', data: {path: '/brokers', children: []}})

    const wrapper = mountBrowser()
    await flushPromises()
    // 首帧放行（路由守卫同口径），但此刻不能打注定可能 404 的接口
    expect(vi.mocked(listZkChildren)).not.toHaveBeenCalled()

    modeLoaded.value = true
    await nextTick()
    await flushPromises()

    expect(vi.mocked(listZkChildren)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(listZkChildren)).toHaveBeenCalledWith(1, '/brokers', false)
  })

  it('首帧未确认、确认结果是 KRaft：依旧不发请求，只展示引导', async () => {
    const wrapper = mountBrowser()
    await flushPromises()
    expect(vi.mocked(listZkChildren)).not.toHaveBeenCalled()

    clusterMode.value = 'KRAFT'
    modeLoaded.value = true
    await nextTick()
    await flushPromises()

    expect(vi.mocked(listZkChildren)).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('KRaft 模式')
  })
})

describe('ZkBrowser 递归截断提示（F2：truncated 必须用 === true 判定）', () => {
  it('truncated=true：出现 warning 提示，告知结果不完整', async () => {
    modeLoaded.value = true
    vi.mocked(listZkChildren).mockResolvedValue({
      code: 0,
      msg: 'ok',
      // 非递归时子节点不带 stat（JSON 里为 null，前端类型上以缺省表达，二者消费等价）
      data: {path: '/brokers', truncated: true, children: [{path: '/brokers/ids'}]},
    })

    const wrapper = mountBrowser()
    await flushPromises()

    expect(wrapper.text()).toContain('/brokers/ids')
    expect(alertTitle(wrapper)).toContain('截断')
  })

  it('truncated=null（未截断，后端显式输出 null）：不出现提示', async () => {
    modeLoaded.value = true
    vi.mocked(listZkChildren).mockResolvedValue({
      code: 0,
      msg: 'ok',
      data: {path: '/brokers', truncated: null, children: [{path: '/brokers/ids'}]},
    })

    const wrapper = mountBrowser()
    await flushPromises()

    expect(wrapper.findAllComponents(ElAlert).length).toBe(0)
  })

  it('truncated=false：不出现提示（不能靠真值判断兜住）', async () => {
    modeLoaded.value = true
    vi.mocked(listZkChildren).mockResolvedValue({
      code: 0,
      msg: 'ok',
      data: {path: '/brokers', truncated: false, children: []},
    })

    const wrapper = mountBrowser()
    await flushPromises()

    expect(wrapper.findAllComponents(ElAlert).length).toBe(0)
  })

  it('正常结果：子节点列表照常渲染', async () => {
    modeLoaded.value = true
    vi.mocked(listZkChildren).mockResolvedValue({
      code: 0,
      msg: 'ok',
      data: {
        path: '/brokers',
        truncated: null,
        children: [{path: '/brokers/ids', stat: {numChildren: 2} as ZkNodeStat}],
      },
    })

    const wrapper = mountBrowser()
    await flushPromises()

    expect(wrapper.findAll('.el-table__row').length).toBe(1)
    expect(wrapper.text()).toContain('/brokers/ids')
  })
})
