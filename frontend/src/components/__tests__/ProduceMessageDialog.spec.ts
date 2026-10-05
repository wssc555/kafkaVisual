// @vitest-environment jsdom
// ProduceMessageDialog 内容挂在 el-dialog 的 teleport 里，需要真实 DOM + teleport 桩。
import '../../test/jsdomPolyfill'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {flushPromises, mount, type VueWrapper} from '@vue/test-utils'
import {nextTick} from 'vue'
import ElementPlus, {ElInputNumber} from 'element-plus'
import i18n from '../../i18n'
import {produceMessage} from '../../api'
import ProduceMessageDialog from '../ProduceMessageDialog.vue'

// ---- 依赖替身 ----
vi.mock('../../api', () => ({
  produceMessage: vi.fn(),
}))
const {confirmMock} = vi.hoisted(() => ({confirmMock: vi.fn()}))
vi.mock('../../composables/useCrudConfirm', () => ({
  useCrudConfirm: () => ({confirm: confirmMock}),
}))

/**
 * 与真实用法一致：先以 visible=false 挂载，再置 true 打开。
 * （el-dialog 的可见性由 modelValue 的变化驱动，初始即为 true 时行为不确定。）
 */
const mountDialog = async (): Promise<VueWrapper<any>> => {
  const wrapper = mount(ProduceMessageDialog, {
    props: {visible: false, clusterId: 1, topic: 'topic-a'},
    global: {
      plugins: [ElementPlus, i18n],
      // teleport 桩让弹窗内容留在组件树内，wrapper.text() 才能覆盖到
      stubs: {teleport: true, HeadersEditor: true},
    },
  })
  await wrapper.setProps({visible: true})
  await nextTick()
  await flushPromises()
  return wrapper
}

/** 两个开关的顺序即模板顺序：[指定分区, 指定时间戳] */
const toggleSwitch = async (wrapper: VueWrapper<any>, index: number, value: boolean) => {
  wrapper.findAllComponents({name: 'ElSwitch'})[index].vm.$emit('update:modelValue', value)
  await nextTick()
}

beforeEach(() => {
  vi.mocked(produceMessage).mockReset()
  confirmMock.mockReset()
  confirmMock.mockResolvedValue(true)
})

describe('ProduceMessageDialog 分区缺省语义文案（F13①）', () => {
  it('未指定分区时提示粘性分区语义，而不是「轮询」', async () => {
    const wrapper = await mountDialog()

    expect(wrapper.text()).toContain('粘性分区')
    expect(wrapper.text()).not.toContain('轮询')
  })
})

describe('ProduceMessageDialog 整数输入约束（F13②）', () => {
  it('指定时间戳：precision=0 / step=1，小数毫秒时间戳在输入端就被禁掉', async () => {
    const wrapper = await mountDialog()

    // 初始未开启开关：不应有任何数字输入框
    expect(wrapper.findAllComponents(ElInputNumber).length).toBe(0)

    await toggleSwitch(wrapper, 1, true)

    const inputs = wrapper.findAllComponents(ElInputNumber)
    expect(inputs.length).toBe(1)
    expect(inputs[0].props('precision')).toBe(0)
    expect(inputs[0].props('step')).toBe(1)
    expect(inputs[0].props('min')).toBe(0)
  })

  it('指定分区：precision=0 / step=1（分区号同样是 int，小数同样会被 Jackson 截断）', async () => {
    const wrapper = await mountDialog()

    await toggleSwitch(wrapper, 0, true)

    const inputs = wrapper.findAllComponents(ElInputNumber)
    expect(inputs.length).toBe(1)
    expect(inputs[0].props('precision')).toBe(0)
    expect(inputs[0].props('step')).toBe(1)
  })
})

describe('ProduceMessageDialog 提交载荷的缺省语义（回归防护）', () => {
  it('开关全关时：只发 topic/value，不携带 partition / timestamp / headers', async () => {
    vi.mocked(produceMessage).mockResolvedValue({
      code: 0,
      msg: 'ok',
      data: {topic: 'topic-a', partition: 0, offset: 1, timestamp: 123},
    })

    const wrapper = await mountDialog()

    // 填 topic 与 value（value @NotNull 但空串合法，这里填非空串）
    const topicInput = wrapper.find('input[placeholder*="必须已存在"]')
    await topicInput.setValue('topic-a')
    const valueInput = wrapper.find('textarea')
    await valueInput.setValue('hello')

    const sendButton = wrapper.findAll('button').find((b) => b.text() === '发送')!
    await sendButton.trigger('click')
    await flushPromises()

    expect(vi.mocked(produceMessage)).toHaveBeenCalledTimes(1)
    // produceMessage(clusterId, data) 两参调用,载荷在第二参
    const payload = vi.mocked(produceMessage).mock.calls[0][1]
    expect(payload.topic).toBe('topic-a')
    expect(payload.value).toBe('hello')
    expect('partition' in payload).toBe(false)
    expect('timestamp' in payload).toBe(false)
    expect('headers' in payload).toBe(false)

    // 成功后关闭弹窗并回传写入位置，父组件据此跳到该 offset
    const visibleEvents = wrapper.emitted('update:visible')!
    expect(visibleEvents[visibleEvents.length - 1]).toEqual([false])
    expect(wrapper.emitted('produced')![0][0]).toEqual({
      topic: 'topic-a',
      partition: 0,
      offset: 1,
      timestamp: 123,
    })
  })

  it('打开「指定分区」并输入分区号：payload 只携带该整数分区号', async () => {
    vi.mocked(produceMessage).mockResolvedValue({
      code: 0,
      msg: 'ok',
      data: {topic: 'topic-a', partition: 2, offset: 9, timestamp: 123},
    })

    const wrapper = await mountDialog()

    await wrapper.find('input[placeholder*="必须已存在"]').setValue('topic-a')
    await wrapper.find('textarea').setValue('hello')
    await toggleSwitch(wrapper, 0, true)

    // 直接对 el-input-number 发 v-model 更新，等价于用户键入 2
    const partitionInput = wrapper.findAllComponents(ElInputNumber)[0]
    partitionInput.vm.$emit('update:modelValue', 2)
    await nextTick()

    const sendButton = wrapper.findAll('button').find((b) => b.text() === '发送')!
    await sendButton.trigger('click')
    await flushPromises()

    const payload = vi.mocked(produceMessage).mock.calls[0][1]
    expect(payload.partition).toBe(2)
    expect('timestamp' in payload).toBe(false)
    expect('headers' in payload).toBe(false)
  })
})
