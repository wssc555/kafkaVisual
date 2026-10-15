/**
 * useFormat 时间格式化（TC-NB-FMT-*）：formatRelativeTime + formatDateTime。
 *
 * node 环境、globals:false：describe/it/expect 必须从 vitest 显式 import。
 * 期望值用与实现同一规则计算，避免写死 locale 文案（node 下 locale 随运行环境变化）。
 */
import {describe, expect, it} from 'vitest'
import {formatDateTime, formatRelativeTime} from '../useFormat'

const rtf = () => new Intl.RelativeTimeFormat(undefined, {numeric: 'auto'})
const isoAgo = (ms: number) => new Date(Date.now() - ms).toISOString()

describe('formatRelativeTime（TC-NB-FMT-*）', () => {
  it('001 空值与非法串 → -', () => {
    expect(formatRelativeTime(null)).toBe('-')
    expect(formatRelativeTime(undefined)).toBe('-')
    expect(formatRelativeTime('')).toBe('-')
    expect(formatRelativeTime('not-a-date')).toBe('-')
  })

  it('002 30 秒前 → 秒级相对文案', () => {
    expect(formatRelativeTime(isoAgo(30_000))).toBe(rtf().format(-30, 'second'))
  })

  it('003 5 分钟前 → 分钟级', () => {
    expect(formatRelativeTime(isoAgo(5 * 60_000))).toBe(rtf().format(-5, 'minute'))
  })

  it('004 3 小时前 → 小时级', () => {
    expect(formatRelativeTime(isoAgo(3 * 3_600_000))).toBe(rtf().format(-3, 'hour'))
  })

  it('005 3 天前 → 天级', () => {
    expect(formatRelativeTime(isoAgo(3 * 86_400_000))).toBe(rtf().format(-3, 'day'))
  })

  it('006 35 天前 → 绝对日期', () => {
    const t = Date.now() - 35 * 86_400_000
    expect(formatRelativeTime(new Date(t).toISOString())).toBe(new Date(t).toLocaleDateString())
  })

  it('007 formatDateTime：ISO 串 → YYYY-MM-DD HH:mm:ss（本地时区）', () => {
    const d = new Date(Date.now() - 3 * 86_400_000)
    const pad = (n: number) => String(n).padStart(2, '0')
    const expected = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
      + ` ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
    expect(formatDateTime(d.toISOString())).toBe(expected)
  })

  it('008 formatDateTime：空值与非法串 → -', () => {
    expect(formatDateTime(null)).toBe('-')
    expect(formatDateTime(undefined)).toBe('-')
    expect(formatDateTime('')).toBe('-')
    expect(formatDateTime('not-a-date')).toBe('-')
  })
})
