import {beforeEach, describe, expect, it, vi} from 'vitest'
import {ElMessageBox} from 'element-plus'
import {ApiError} from '../../api'
import {parseEarliestOffset, useOffsetRecovery} from '../useOffsetRecovery'

// 运行在 vitest.config.ts 的默认 node 环境（本文件不依赖 DOM）。
// element-plus 整包在 node 下无法保证可运行，本用例只关心 ElMessageBox.confirm
// 的「确认 / 取消」两个出口，直接以桩替身隔离。
vi.mock('element-plus', () => ({
  ElMessageBox: {confirm: vi.fn()},
}))

const confirmMock = vi.mocked(ElMessageBox.confirm)

/** 规范 §6.1：后端 40001 的原始文案（auto.offset.reset=none 下 seek 越界） */
const OUT_OF_RANGE_42 =
  'Offset 999 is out of range for topic-a-0, earliest available offset is 42'

beforeEach(() => {
  confirmMock.mockReset()
})

describe('parseEarliestOffset（F3：从 40001 文案中解析 earliest offset）', () => {
  it('解析规范给出的标准文案', () => {
    expect(parseEarliestOffset(OUT_OF_RANGE_42)).toBe(42)
  })

  it('文案尾部附带额外信息时仍能命中（宽松匹配是有意设计）', () => {
    expect(parseEarliestOffset(OUT_OF_RANGE_42 + ' (partition 0)')).toBe(42)
  })

  it('earliest 为 0 时必须返回 0（不能用 falsy 判断丢失合法值）', () => {
    expect(parseEarliestOffset('Offset 1 is out of range for t-0, earliest available offset is 0')).toBe(0)
  })

  it('空串 / null / undefined 返回 null', () => {
    expect(parseEarliestOffset('')).toBeNull()
    expect(parseEarliestOffset(null)).toBeNull()
    expect(parseEarliestOffset(undefined)).toBeNull()
  })

  it('完全不相关的错误文案返回 null', () => {
    expect(parseEarliestOffset('Topic not found: no-such')).toBeNull()
    expect(parseEarliestOffset('Kafka call timed out after 10000ms: describeTopics')).toBeNull()
  })

  it('负数不是合法 offset（\\d+ 不含符号）', () => {
    expect(parseEarliestOffset('earliest available offset is -3')).toBeNull()
  })

  it('超出 Number.MAX_SAFE_INTEGER 的数字返回 null，避免后续计算失真', () => {
    expect(parseEarliestOffset('earliest available offset is 99999999999999999999')).toBeNull()
  })
})

describe('offerRecovery（F3：40001 的恢复引导）', () => {
  const {offerRecovery} = useOffsetRecovery()

  it('40001 且文案可解析：弹确认框并返回 earliest offset', async () => {
    // element-plus 2.14.6 把 MessageBoxData 定义成 MessageBoxInputData & Action（对象 & 字符串联合，交叉退化），
    // 'confirm' 类型上不可赋值；运行时 confirm 分支仍 resolve 动作字符串，按运行时契约断言
    confirmMock.mockResolvedValue('confirm' as never)

    const result = await offerRecovery(new ApiError(OUT_OF_RANGE_42, 40001, 400))

    expect(result).toBe(42)
    expect(confirmMock).toHaveBeenCalledTimes(1)
    const [message, title, options] = confirmMock.mock.calls[0]
    expect(message).toContain('42')
    expect(title).toBe('Offset 越界')
    expect((options as any).confirmButtonText).toContain('42')
  })

  it('普通 Error（非 ApiError）一律不接管 —— 网络层异常没有业务码', async () => {
    const result = await offerRecovery(new Error('Offset 1 is out of range, earliest available offset is 7'))

    expect(result).toBeNull()
    expect(confirmMock).not.toHaveBeenCalled()
  })

  it('非 ApiError 但伪造了 code 字段的对象也不接管（instanceof 判定的意义所在）', async () => {
    const fake = {code: 40001, message: 'earliest available offset is 7'}

    const result = await offerRecovery(fake)

    expect(result).toBeNull()
    expect(confirmMock).not.toHaveBeenCalled()
  })

  it('ApiError 但业务码不是 40001：不接管', async () => {
    const result = await offerRecovery(new ApiError('earliest available offset is 7', 50001, 500))

    expect(result).toBeNull()
    expect(confirmMock).not.toHaveBeenCalled()
  })

  it('40001 但文案解析不出 offset：不接管也不弹框', async () => {
    const result = await offerRecovery(new ApiError('Offset is out of range', 40001, 400))

    expect(result).toBeNull()
    expect(confirmMock).not.toHaveBeenCalled()
  })

  it('用户取消（confirm reject）：返回 null 而不是抛错', async () => {
    confirmMock.mockRejectedValue(new Error('cancel'))

    const result = await offerRecovery(new ApiError(OUT_OF_RANGE_42, 40001, 400))

    expect(result).toBeNull()
    expect(confirmMock).toHaveBeenCalledTimes(1)
  })
})
