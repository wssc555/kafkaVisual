/**
 * 展示层格式化工具。
 * 后端返回原始字节数 / 毫秒时间戳，统一在此转换为人类可读文本。
 */

/** 字节数 → B / KB / MB / GB / TB */
export const formatBytes = (bytes: number): string => {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(2) + ' MB'
  if (bytes < 1024 * 1024 * 1024 * 1024)
    return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  return (bytes / 1024 / 1024 / 1024 / 1024).toFixed(2) + ' TB'
}

/** 毫秒时间戳 → 本地时间字符串；空值返回 '-' */
export const formatTimestamp = (ts?: number | null): string => {
  if (ts === null || ts === undefined || Number.isNaN(ts)) return '-'
  return new Date(ts).toLocaleString()
}

/** 大数字千分位，用于 offset / lag 等长整数 */
export const formatNumber = (n: number): string => {
  if (n === null || n === undefined || Number.isNaN(n)) return '-'
  return n.toLocaleString()
}

/** 空值占位：null / undefined / 空串统一显示为 '-' */
export const orDash = (v?: string | number | null): string => {
  if (v === null || v === undefined || v === '') return '-'
  return String(v)
}

/**
 * ISO-8601 时间 → 相对时间（"3 分钟前"）。
 * 注意入参是 ISO 字符串（后端口径），不是毫秒时间戳——勿与 formatTimestamp 混用。
 * 超过 30 天回退绝对日期；空值/非法串返回 '-'。
 */
export const formatRelativeTime = (iso?: string | null): string => {
  if (!iso) return '-'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return '-'
  const diff = t - Date.now()
  const abs = Math.abs(diff)
  // 取运行时默认 locale，中英文自动跟随
  const rtf = new Intl.RelativeTimeFormat(undefined, {numeric: 'auto'})
  if (abs < 60_000) return rtf.format(Math.round(diff / 1000), 'second')
  if (abs < 3_600_000) return rtf.format(Math.round(diff / 60_000), 'minute')
  if (abs < 86_400_000) return rtf.format(Math.round(diff / 3_600_000), 'hour')
  if (abs < 30 * 86_400_000) return rtf.format(Math.round(diff / 86_400_000), 'day')
  return new Date(t).toLocaleDateString()
}

/**
 * ISO-8601 时间 → 'YYYY-MM-DD HH:mm:ss'（本地时区）。
 * 注意入参是 ISO 字符串（后端口径），不是毫秒时间戳——勿与 formatTimestamp 混用。
 * 后端存的是 UTC（Instant.toString），此处按浏览器本地时区展示；空值/非法串返回 '-'。
 */
export const formatDateTime = (iso?: string | null): string => {
  if (!iso) return '-'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return '-'
  const d = new Date(t)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + ` ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 组合式导出，便于组件按需解构 */
export const useFormat = () => ({
  formatBytes,
  formatTimestamp,
  formatNumber,
  orDash,
  formatRelativeTime,
  formatDateTime,
})
