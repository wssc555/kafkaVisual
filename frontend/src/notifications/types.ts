/**
 * 站内通知的通用数据模型。
 *
 * 与具体来源解耦：铃铛 UI 只消费 `NotificationItem`，来源差异由
 * `adapters/` 下的适配层抹平（新增来源 = 新增 adapter，UI 层不动）。
 */

/** 通知严重度（一期对齐告警 severity） */
export type NotificationSeverity = 'INFO' | 'WARN' | 'CRITICAL'

/** 通知来源。一期只有 ALERT；'SYSTEM' / 'UPGRADE' 等为未来扩展预留，暂不实现 */
export type NotificationSource = 'ALERT'

export interface NotificationItem {
  /** 全局唯一，命名空间前缀隔离多来源：`alert:{id}` */
  id: string
  source: NotificationSource
  severity: NotificationSeverity
  title: string
  summary?: string | null
  clusterName?: string | null
  /** ISO-8601 字符串（后端口径），非毫秒时间戳 */
  firedAt?: string | null
  /** 已读 = 已 ack（后端 acknowledged_at 非空） */
  read: boolean
  /** 是否已自动恢复（state === 'RECOVERED'） */
  recovered: boolean
  /** 点击条目的跳转目标 */
  link: {path: string; query: Record<string, string>}
  /** 原始数据，详情与后续扩展用 */
  raw: unknown
}
