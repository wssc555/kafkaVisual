/**
 * notification 域文案（顶栏通知铃铛 + 通知面板）。
 * 约定：export const zh = {...}; export type Schema = typeof zh; export const en: Schema = {...}
 *
 * 注意：vue-i18n 消息里 `@` / `|` 是语法字符，文案内不要出现裸 @ 或 |；
 * `{n}` / `{ok}` / `{total}` / `{msg}` 是命名插值（合法）。
 */
export const zh = {
  title: '通知',
  unreadSuffix: '{n} 条未读',
  markRead: '标记已读',
  markAllRead: '全部已读',
  markAllDone: '已标记 {ok}/{total}',
  refresh: '刷新',
  empty: '暂无通知',
  loadFailed: '加载失败',
  retry: '重试',
  viewAll: '查看全部',
  recovered: '已恢复',
  ackFailed: '标记已读失败：{msg}',
  ariaLabel: '站内通知',
}

export type Schema = typeof zh

export const en: Schema = {
  title: 'Notifications',
  unreadSuffix: '{n} unread',
  markRead: 'Mark as read',
  markAllRead: 'Mark all as read',
  markAllDone: 'Marked {ok}/{total}',
  refresh: 'Refresh',
  empty: 'No notifications',
  loadFailed: 'Failed to load',
  retry: 'Retry',
  viewAll: 'View all',
  recovered: 'Recovered',
  ackFailed: 'Failed to mark as read: {msg}',
  ariaLabel: 'Notifications',
}
