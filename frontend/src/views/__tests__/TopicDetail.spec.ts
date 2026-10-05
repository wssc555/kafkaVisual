// @vitest-environment jsdom
// TopicDetail 是页面级组件：真实 Element Plus 渲染 + 桩掉 6 个子弹窗/表格子组件，
// 只验证本文件真正改动的东西 —— F3 的 40001 恢复链路与 F8 的按码分支。
import '../../test/jsdomPolyfill'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {flushPromises, mount, type VueWrapper} from '@vue/test-utils'
import {computed, ref} from 'vue'
import ElementPlus, {ElEmpty, ElMessage, ElMessageBox} from 'element-plus'
import i18n from '../../i18n'
import {ApiError, getTopicDetail, type MessageQueryResult, queryMessages} from '../../api'
import {GlobalStateKey} from '../../composables/useGlobalState'
import TopicDetail from '../TopicDetail.vue'

// 路由参数来自 /topics/:name；本组件只读 useRoute，不真正导航。
// 注意 onMounted 深链分支读 route.query.tab，mock 必须带 query 键
vi.mock('vue-router', () => ({
  useRoute: () => ({params: {name: 'topic-a'}, query: {}}),
}))

// ApiError 必须用真实实现：view 里用 `e instanceof ApiError` 门控恢复路径，
// 若这里返回假类，instanceof 判定就测不到了
vi.mock('../../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api')>()
  return {
    ...actual,
    getTopicDetail: vi.fn(),
    queryMessages: vi.fn(),
    offsetsForTimes: vi.fn(),
  }
})

/** ElMessageBox.confirm 的替身句柄（每个用例前重建，afterEach 恢复） */
let confirmSpy: ReturnType<typeof vi.spyOn>

const queryResult = (offset: number, records: MessageQueryResult['records'], hasMore = false) => ({
  code: 0 as const,
  msg: 'ok',
  data: {
    topic: 'topic-a',
    partition: 0,
    startOffset: offset,
    records,
    totalReturned: records.length,
    endOffset: offset + records.length,
    hasMore,
  },
})

const oneRecord = (offset: number): MessageQueryResult['records'] => [
  {topic: 'topic-a', partition: 0, offset, timestamp: 1, timestampType: 'CREATE_TIME', value: 'v', headers: {}},
]

const OUT_OF_RANGE_42 =
  'Offset 999 is out of range for topic-a-0, earliest available offset is 42'

const mountDetail = async (): Promise<VueWrapper<any>> => {
  const wrapper = mount(TopicDetail, {
    global: {
      plugins: [ElementPlus, i18n],
      // loadTopic/searchMessages 都在 activeClusterId == null 时直接 return：
      // 必须提供激活集群，否则 getTopicDetail 从不调用、页面停在归档空态
      provide: {
        [GlobalStateKey]: {
          activeClusterId: ref(1),
          activeCluster: computed(() => null),
        },
      },
      stubs: {
        MessageTable: true,
        MessageDetail: true,
        OffsetLookupResult: true,
        EditTopicConfigsDialog: true,
        ExpandPartitionsDialog: true,
        ProduceMessageDialog: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

const clickButton = async (wrapper: VueWrapper<any>, text: string) => {
  const btn = wrapper.findAll('button').find((b) => b.text() === text)
  if (!btn) throw new Error(`button not found: ${text}`)
  await btn.trigger('click')
}

beforeEach(() => {
  vi.mocked(getTopicDetail).mockReset()
  vi.mocked(queryMessages).mockReset()
  confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
})
afterEach(() => {
  confirmSpy.mockRestore()
})

describe('Topic 详情加载失败按码分支（F8：ApiError.code 的真实消费点）', () => {
  it('40401 = Topic 不存在：描述里明确「不存在」而不是笼统的加载失败', async () => {
    vi.mocked(getTopicDetail).mockRejectedValue(new ApiError('Topic not found: topic-a', 40401, 404))

    const wrapper = await mountDetail()

    const empty = wrapper.findComponent(ElEmpty)
    expect(empty.exists()).toBe(true)
    const description = String(empty.props('description'))
    expect(description).toContain('不存在')
    expect(description).not.toContain('加载失败：')
    // 两种恢复入口都在，只是主次随错误码互换
    expect(wrapper.text()).toContain('重试')
    expect(wrapper.text()).toContain('返回集群')
  })

  it('50001 等其他错误：仍按「加载失败」展示，允许重试', async () => {
    vi.mocked(getTopicDetail).mockRejectedValue(
      new ApiError('Kafka call timed out after 10000ms: describeTopics', 50001, 500),
    )

    const wrapper = await mountDetail()

    const description = String(wrapper.findComponent(ElEmpty).props('description'))
    expect(description).toContain('加载失败')
    expect(description).not.toContain('不存在')
  })
})

describe('40001 offset 越界的恢复链路（F3）', () => {
  beforeEach(() => {
    vi.mocked(getTopicDetail).mockResolvedValue({
      code: 0,
      msg: 'ok',
      data: {
        name: 'topic-a',
        partitions: [{partition: 0, leader: 0, replicas: [0], isr: [0], beginningOffset: 0, endOffset: 10}],
      },
    })
  })

  it('越界且文案带 earliest offset：二次确认后改写 offset 并重查', async () => {
    const wrapper = await mountDetail()

    vi.mocked(queryMessages)
      .mockRejectedValueOnce(new ApiError(OUT_OF_RANGE_42, 40001, 400))
      .mockResolvedValueOnce(queryResult(42, []))

    await clickButton(wrapper, '查询')
    await flushPromises()

    expect(vi.mocked(queryMessages)).toHaveBeenCalledTimes(2)
    // API 签名为 queryMessages(clusterId, params)：参数对象在第二个实参
    expect(vi.mocked(queryMessages).mock.calls[1][1]).toEqual({
      topic: 'topic-a',
      partition: 0,
      offset: 42,
      count: 100,
    })
  })

  it('用户取消恢复：不重查，原始错误照常弹出', async () => {
    const errorSpy = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountDetail()

    vi.mocked(queryMessages).mockRejectedValue(new ApiError(OUT_OF_RANGE_42, 40001, 400))
    confirmSpy.mockRejectedValueOnce(new Error('cancel'))

    await clickButton(wrapper, '查询')
    await flushPromises()

    expect(vi.mocked(queryMessages)).toHaveBeenCalledTimes(1)
    expect(errorSpy).toHaveBeenCalledWith(OUT_OF_RANGE_42)
  })

  it('重查后再次越界：不再弹确认框（防无限循环），只查两次', async () => {
    const wrapper = await mountDetail()

    vi.mocked(queryMessages).mockRejectedValue(new ApiError(OUT_OF_RANGE_42, 40001, 400))

    await clickButton(wrapper, '查询')
    await flushPromises()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(vi.mocked(queryMessages)).toHaveBeenCalledTimes(2)
  })

  it('「加载更多」撞上 retention 前进：同样走恢复路径，改为从 earliest 重查', async () => {
    const wrapper = await mountDetail()

    // 首查正常返回 1 条（offset 0）且还有更多
    vi.mocked(queryMessages)
      .mockResolvedValueOnce(queryResult(0, oneRecord(0), true))
      // loadMore 从 lastMsg.offset + 1 = 1 开始，此时 retention 已把 1 清掉
      .mockRejectedValueOnce(
        new ApiError('Offset 1 is out of range for topic-a-0, earliest available offset is 42', 40001, 400),
      )
      .mockResolvedValueOnce(queryResult(42, []))

    await clickButton(wrapper, '查询')
    await flushPromises()
    await clickButton(wrapper, '加载更多')
    await flushPromises()

    const calls = vi.mocked(queryMessages).mock.calls
    expect(calls.length).toBe(3)
    expect(calls[1][1]).toEqual({topic: 'topic-a', partition: 0, offset: 1, count: 100})
    expect(calls[2][1]).toEqual({topic: 'topic-a', partition: 0, offset: 42, count: 100})
  })

  it('hasMore=false：「加载更多」不渲染，展示已到末尾', async () => {
    const wrapper = await mountDetail()

    vi.mocked(queryMessages).mockResolvedValue(queryResult(0, oneRecord(0), false))

    await clickButton(wrapper, '查询')
    await flushPromises()

    expect(wrapper.text()).toContain('已到末尾')
    expect(wrapper.findAll('button').find((b) => b.text() === '加载更多')).toBeUndefined()
  })
})
