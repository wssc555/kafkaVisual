/**
 * 巡检告警 → 通用通知项的适配层。
 *
 * 纯函数、无副作用、无 API 调用：便于 node 环境直接单测，
 * 也让「通知面板」与「告警中心」两份 UI 共用同一套字段口径。
 */
import type {AlertRecordItem} from '../../api/inspection'
import type {NotificationItem} from '../types'

const ALERT_ID_PREFIX = 'alert:'

/** 巡检告警记录 → 通用通知项（纯函数，无副作用） */
export const alertToNotification = (a: AlertRecordItem): NotificationItem => ({
  id: `${ALERT_ID_PREFIX}${a.id}`,
  source: 'ALERT',
  severity: a.severity,
  title: a.title,
  summary: a.message,
  clusterName: a.clusterName,
  firedAt: a.firedAt,
  read: Boolean(a.acknowledgedAt),
  recovered: a.state === 'RECOVERED',
  link: {path: '/inspection/alerts', query: {highlight: String(a.id)}},
  raw: a,
})

/**
 * 从通知项解析后端告警 id（ack 用）。
 * 非 ALERT 来源或 raw 形态不符时返回 null——一期只有告警一种来源。
 */
export const alertIdOf = (n: NotificationItem): number | null => {
  if (n.source !== 'ALERT') return null
  const raw = n.raw as AlertRecordItem | undefined
  return raw && typeof raw.id === 'number' ? raw.id : null
}
