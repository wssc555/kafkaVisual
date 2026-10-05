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

/** 组合式导出，便于组件按需解构 */
export const useFormat = () => ({
  formatBytes,
  formatTimestamp,
  formatNumber,
  orDash,
})
