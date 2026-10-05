// @vitest-environment jsdom
// 组件渲染需要 DOM；api 层（TC-FE-*）仍在 node 环境，二者通过此 docblock 隔离。
import '../../test/jsdomPolyfill'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {flushPromises, mount, type VueWrapper} from '@vue/test-utils'
import {ref} from 'vue'
import ElementPlus, {ElMessage, ElTable} from 'element-plus'
import i18n from '../../i18n'
import {deleteTopic, getTopics} from '../../api'
import {GlobalStateKey} from '../../composables/useGlobalState'
import TopicList from '../TopicList.vue'

// ---- 依赖替身 ----
vi.mock('../../api', () => ({
  getTopics: vi.fn(),
  deleteTopic: vi.fn(),
  getArchiveTopics: vi.fn(),
}))
const {confirmMock} = vi.hoisted(() => ({confirmMock: vi.fn()}))
vi.mock('../../composables/useCrudConfirm', () => ({
  useCrudConfirm: () => ({confirm: confirmMock}),
}))

const mountList = (): VueWrapper<any> =>
  mount(TopicList, {
    global: {
      plugins: [ElementPlus, i18n],
      // loadTopics 在 activeClusterId == null 时直接 return —— 必须提供激活集群，
      // 否则 getTopics 永远不会被调用（组件按需请求语义，见 TopicList.vue loadTopics 门控）
      provide: {
        [GlobalStateKey]: {
          activeClusterId: ref(1),
          loadFavorites: vi.fn().mockResolvedValue(undefined),
          toggleFavorite: vi.fn().mockResolvedValue(undefined),
          isFavorite: () => false,
        },
      },
    },
  })

const findButton = (wrapper: VueWrapper<any>, text: string) =>
  wrapper.findAll('button').find((b) => b.text() === text)

beforeEach(() => {
  vi.mocked(getTopics).mockReset()
  vi.mocked(deleteTopic).mockReset()
  confirmMock.mockReset()
  confirmMock.mockResolvedValue(true)
})

describe('TopicList：GET /topics 的 data 是 string[]（F1 回归防护）', () => {
  it('名称列直接渲染 topic 字符串，不出现 undefined / [object Object]', async () => {
    vi.mocked(getTopics).mockResolvedValue({code: 0, msg: 'ok', data: ['topic-a', 'topic-b']})

    const wrapper = mountList()
    await flushPromises()

    expect(vi.mocked(getTopics)).toHaveBeenCalledTimes(1)
    // 修复前：`prop="name"` 对字符串取属性 → 名称列恒为空，'topic-a' 根本不出现
    expect(wrapper.text()).toContain('topic-a')
    expect(wrapper.text()).toContain('topic-b')
    expect(wrapper.text()).not.toContain('undefined')
    expect(wrapper.text()).not.toContain('[object Object]')
  })

  it('空列表渲染为空表而不是报错', async () => {
    vi.mocked(getTopics).mockResolvedValue({code: 0, msg: 'ok', data: []})

    const wrapper = mountList()
    await flushPromises()

    expect(wrapper.findAll('.el-table__row').length).toBe(0)
  })

  it('行点击向父组件 emit view，payload 就是 topic 名本身', async () => {
    vi.mocked(getTopics).mockResolvedValue({code: 0, msg: 'ok', data: ['topic-a']})

    const wrapper = mountList()
    await flushPromises()

    // 修复前：`(row) => $emit('view', row.name)` → 路由跳到 /topics/undefined
    await wrapper.findAll('.el-table__row')[0].trigger('click')

    expect(wrapper.emitted('view')).toEqual([['topic-a']])
  })

  it('点击删除：确认通过后按 topic 名调用 DELETE，并重新拉取列表', async () => {
    vi.mocked(getTopics)
      .mockResolvedValueOnce({code: 0, msg: 'ok', data: ['topic-a']})
      .mockResolvedValue({code: 0, msg: 'ok', data: []})
    vi.mocked(deleteTopic).mockResolvedValue({code: 0, msg: 'ok', data: {name: 'topic-a', deleted: true}})

    const wrapper = mountList()
    await flushPromises()

    await findButton(wrapper, '删除')!.trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(String(confirmMock.mock.calls[0][0])).toContain('topic-a')
    expect(vi.mocked(deleteTopic)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(deleteTopic)).toHaveBeenCalledWith(1, 'topic-a')
    // 删除成功后刷新列表
    expect(vi.mocked(getTopics)).toHaveBeenCalledTimes(2)
  })

  it('确认框取消：不发起删除请求', async () => {
    vi.mocked(getTopics).mockResolvedValue({code: 0, msg: 'ok', data: ['topic-a']})
    confirmMock.mockResolvedValue(false)

    const wrapper = mountList()
    await flushPromises()

    await findButton(wrapper, '删除')!.trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(vi.mocked(deleteTopic)).not.toHaveBeenCalled()
    expect(vi.mocked(getTopics)).toHaveBeenCalledTimes(1)
  })

  it('el-table 收到的 data 是 TopicRow 数组（name 可渲染，含 deleted 标记）', async () => {
    vi.mocked(getTopics).mockResolvedValue({code: 0, msg: 'ok', data: ['t1', 't2']})

    const wrapper = mountList()
    await flushPromises()

    const table = wrapper.findComponent(ElTable)
    // VTU 对 ElTable 的 props 泛型推导不出（keyof 退化为 never），转 never 绕过；运行时行为正常。
    // 修复前：`prop="name"` 对字符串取属性 → 名称列恒为空；现组件显式包装为 TopicRow（name + deleted 标记）
    expect(table.props('data' as never)).toEqual([
      {name: 't1', deleted: false},
      {name: 't2', deleted: false},
    ])
  })

  it('列表加载失败：给出错误提示而不是静默空白', async () => {
    vi.mocked(getTopics).mockRejectedValue(new Error('Kafka call timed out'))
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as any)

    const wrapper = mountList()
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledTimes(1)
    expect(String(errorSpy.mock.calls[0][0])).toContain('Kafka call timed out')
    errorSpy.mockRestore()
  })
})
