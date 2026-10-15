// @vitest-environment jsdom
// 组件渲染需要 DOM；api / composable 层仍在 node 环境，二者通过此 docblock 隔离。
import '../../test/jsdomPolyfill'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {defineComponent, h, nextTick, ref} from 'vue'
import {flushPromises, mount, type VueWrapper} from '@vue/test-utils'
import ElementPlus, {ElMessage} from 'element-plus'
import i18n from '../../i18n'
import type {AlertRecordItem} from '../../api/inspection'
import {alertToNotification} from '../../notifications/adapters/alert'
import type {NotificationItem} from '../../notifications/types'
import {NotificationsKey, type NotificationsState} from '../../composables/useNotifications'
import NotificationBell from '../NotificationBell.vue'

// ---- 依赖替身 ----
const pushMock = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({push: pushMock}),
  useRoute: () => ({query: {}}),
}))

/**
 * el-popover 渲染函数桩：绕开 popper 实例（jsdom 下无布局），
 * 同时渲染 reference（铃铛/徽标）与 default（面板内容）。
 */
const ElPopoverStub = defineComponent({
  props: ['visible'],
  emits: ['update:visible'],
  setup: (props, {slots}) => () => [
    slots.reference?.(),
    props.visible ? h('div', {class: 'popover-stub'}, slots.default?.()) : null,
  ],
})

const alert = (over: Partial<AlertRecordItem> = {}): AlertRecordItem => ({
  id: 7,
  clusterId: 1,
  ruleId: 2,
  severity: 'WARN',
  state: 'FIRING',
  title: 'lag 超阈值',
  ...over,
})

const makeState = (over: Partial<NotificationsState> = {}): NotificationsState => ({
  unreadCount: ref(0),
  items: ref<NotificationItem[]>([]),
  listLoading: ref(false),
  listError: ref(false),
  ackingIds: ref<Set<string>>(new Set()),
  ackingAll: ref(false),
  refreshUnreadCount: vi.fn().mockResolvedValue(undefined),
  loadList: vi.fn().mockResolvedValue(undefined),
  ackItem: vi.fn().mockResolvedValue(undefined),
  ackAll: vi.fn().mockResolvedValue({ok: 0, fail: 0, total: 0}),
  ...over,
})

const mountBell = (state: NotificationsState): VueWrapper<any> =>
  mount(NotificationBell, {
    global: {
      // 挂载型 spec 的 plugins 必须含 i18n（既有约定）
      plugins: [ElementPlus, i18n],
      stubs: {ElPopover: ElPopoverStub},
      provide: {[NotificationsKey]: state},
    },
  })

/** 展开面板（内部 ref 经 vm 代理写入） */
const openPanel = async (wrapper: VueWrapper<any>) => {
  ;(wrapper.vm as any).panelOpen = true
  await nextTick()
  await flushPromises()
}

const findButton = (wrapper: VueWrapper<any>, text: string) =>
  wrapper.findAll('button').find((b) => b.text() === text)

const tr = (key: string) => i18n.global.t(key)

beforeEach(() => {
  pushMock.mockReset()
})

afterEach(() => {
  // ElMessage spy 等还原，避免跨用例泄漏
  vi.restoreAllMocks()
})

describe('NotificationBell（TC-NB-CMP-*）', () => {
  it('001 unreadCount=0 → 无徽标', () => {
    const wrapper = mountBell(makeState({unreadCount: ref(0)}))

    expect(wrapper.find('.el-badge__content').exists()).toBe(false)
  })

  it('002 unreadCount=150 → 徽标显示 99+', () => {
    const wrapper = mountBell(makeState({unreadCount: ref(150)}))

    expect(wrapper.find('.el-badge__content').text()).toBe('99+')
  })

  it('003 打开面板 → 调用 loadList', async () => {
    const state = makeState()
    const wrapper = mountBell(state)

    await openPanel(wrapper)

    expect(state.loadList).toHaveBeenCalledTimes(1)
  })

  it('004 空态渲染 notification.empty 文案', async () => {
    const wrapper = mountBell(makeState({items: ref([])}))

    await openPanel(wrapper)

    expect(wrapper.text()).toContain(tr('notification.empty'))
  })

  it('005 listError → 点击 retry 再次调用 loadList', async () => {
    const state = makeState({listError: ref(true), items: ref([alertToNotification(alert())])})
    const wrapper = mountBell(state)
    await openPanel(wrapper)

    const retry = findButton(wrapper, tr('notification.retry'))
    expect(retry).toBeTruthy()
    await retry!.trigger('click')

    // 共 2 次：openPanel 的 watch 触发 1 次 + retry 点击 1 次
    expect(state.loadList).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain(tr('notification.loadFailed'))
  })

  it('006 点击「标记已读」→ ackItem 以对应通知项调用', async () => {
    const n = alertToNotification(alert({id: 7}))
    const state = makeState({items: ref([n])})
    const wrapper = mountBell(state)
    await openPanel(wrapper)

    await findButton(wrapper, tr('notification.markRead'))!.trigger('click')

    expect(state.ackItem).toHaveBeenCalledTimes(1)
    expect(state.ackItem).toHaveBeenCalledWith(n)
  })

  it('007 点击条目标题 → router.push 收到深链 query', async () => {
    const wrapper = mountBell(makeState({items: ref([alertToNotification(alert({id: 7}))])}))
    await openPanel(wrapper)

    await wrapper.find('.nb-item-title').trigger('click')

    expect(pushMock).toHaveBeenCalledWith({
      path: '/inspection/alerts',
      query: {highlight: '7'},
    })
  })

  it('008 点击「查看全部」→ router.push 收到 /inspection/alerts', async () => {
    const wrapper = mountBell(makeState())
    await openPanel(wrapper)

    await findButton(wrapper, tr('notification.viewAll'))!.trigger('click')

    expect(pushMock).toHaveBeenCalledWith('/inspection/alerts')
  })

  it('009 点击「全部已读」→ 调用 ackAll 并弹出汇总提示', async () => {
    const successSpy = vi.spyOn(ElMessage, 'success')
    const state = makeState({
      unreadCount: ref(1),
      items: ref([alertToNotification(alert({id: 7}))]),
      ackAll: vi.fn().mockResolvedValue({ok: 1, fail: 0, total: 1}),
    })
    const wrapper = mountBell(state)
    await openPanel(wrapper)

    await findButton(wrapper, tr('notification.markAllRead'))!.trigger('click')
    await flushPromises()

    expect(state.ackAll).toHaveBeenCalledTimes(1)
    expect(successSpy).toHaveBeenCalledTimes(1)
  })

  it('010 单条 ack 失败 → 弹出错误提示', async () => {
    const errorSpy = vi.spyOn(ElMessage, 'error')
    const state = makeState({
      items: ref([alertToNotification(alert({id: 7}))]),
      ackItem: vi.fn().mockRejectedValue(new Error('boom')),
    })
    const wrapper = mountBell(state)
    await openPanel(wrapper)

    await findButton(wrapper, tr('notification.markRead'))!.trigger('click')
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledTimes(1)
  })

  it('011 severity 三档 → danger/warning/info', async () => {
    const wrapper = mountBell(makeState({items: ref([
      alertToNotification(alert({id: 1, severity: 'CRITICAL'})),
      alertToNotification(alert({id: 2, severity: 'WARN'})),
      alertToNotification(alert({id: 3, severity: 'INFO'})),
    ])}))
    await openPanel(wrapper)

    expect(wrapper.find('.el-tag--danger').exists()).toBe(true)
    expect(wrapper.find('.el-tag--warning').exists()).toBe(true)
    expect(wrapper.find('.el-tag--info').exists()).toBe(true)
  })

  it('012 recovered 条目展示「已恢复」标记', async () => {
    const wrapper = mountBell(makeState({items: ref([alertToNotification(alert({id: 7, state: 'RECOVERED'}))])}))
    await openPanel(wrapper)

    expect(wrapper.text()).toContain(tr('notification.recovered'))
  })
})
