/**
 * 告警 → 通知项适配层（TC-NB-ADA-*）。
 *
 * node 环境、globals:false：describe/it/expect 必须从 vitest 显式 import。
 * 不 mock 任何模块——本文件只依赖类型与纯函数。
 */
import {describe, expect, it} from 'vitest'
import type {AlertRecordItem} from '../../api/inspection'
import {alertIdOf, alertToNotification} from '../adapters/alert'
import type {NotificationItem} from '../types'

/** 造数：只给必填字段，其余按用例显式覆盖 */
const alert = (over: Partial<AlertRecordItem> = {}): AlertRecordItem => ({
  id: 7,
  clusterId: 1,
  ruleId: 2,
  severity: 'WARN',
  state: 'FIRING',
  title: 'lag 超阈值',
  ...over,
})

describe('alertToNotification（TC-NB-ADA-*）', () => {
  it('001 id 为 alert:{id} 前缀形式', () => {
    expect(alertToNotification(alert({id: 42})).id).toBe('alert:42')
  })

  it('002 acknowledgedAt 非空 → read=true；null → read=false', () => {
    expect(alertToNotification(alert({acknowledgedAt: '2026-10-14T10:00:00Z'})).read).toBe(true)
    expect(alertToNotification(alert({acknowledgedAt: null})).read).toBe(false)
    // 字段缺失（后端 NON_NULL 省略）同为未读
    expect(alertToNotification(alert()).read).toBe(false)
  })

  it('003 state=RECOVERED → recovered=true；FIRING → false', () => {
    expect(alertToNotification(alert({state: 'RECOVERED'})).recovered).toBe(true)
    expect(alertToNotification(alert({state: 'FIRING'})).recovered).toBe(false)
  })

  it('004 link 指向告警中心且带 highlight 深链', () => {
    const n = alertToNotification(alert({id: 7}))
    expect(n.link.path).toBe('/inspection/alerts')
    expect(n.link.query.highlight).toBe('7')
  })

  it('005 clusterName/message/firedAt 原样透传，null 保持 null', () => {
    const n = alertToNotification(
      alert({
        clusterName: 'prod-1',
        message: 'lag=12000',
        firedAt: '2026-10-14T09:30:00Z',
      }),
    )
    expect(n.clusterName).toBe('prod-1')
    expect(n.summary).toBe('lag=12000')
    expect(n.firedAt).toBe('2026-10-14T09:30:00Z')

    const empty = alertToNotification(alert({clusterName: null, message: null, firedAt: null}))
    expect(empty.clusterName).toBeNull()
    expect(empty.summary).toBeNull()
    expect(empty.firedAt).toBeNull()
  })

  it('006 severity 原样透传', () => {
    expect(alertToNotification(alert({severity: 'CRITICAL'})).severity).toBe('CRITICAL')
    expect(alertToNotification(alert({severity: 'INFO'})).severity).toBe('INFO')
  })

  it('007 alertIdOf 返回数字 id；非 ALERT 来源或 raw 缺失时返回 null', () => {
    const n = alertToNotification(alert({id: 7}))
    expect(alertIdOf(n)).toBe(7)
    expect(alertIdOf({...n, source: 'ALERT', raw: undefined})).toBeNull()
    expect(alertIdOf({...n, source: 'ALERT', raw: {}})).toBeNull()
  })
})

describe('alertIdOf 对非告警来源（TC-NB-ADA-007 扩展）', () => {
  it('source 不是 ALERT 时直接返回 null（一期不存在该形态，防御性断言）', () => {
    const n = alertToNotification(alert({id: 7}))
    const other = {...n, source: 'SYSTEM'} as unknown as NotificationItem
    expect(alertIdOf(other)).toBeNull()
  })
})
