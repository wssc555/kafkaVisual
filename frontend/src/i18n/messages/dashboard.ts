/**
 * dashboard 域文案（多集群总览 Dashboard.vue）。
 * 约定：export const zh = {...}; export type Schema = typeof zh; export const en: Schema = {...}
 */
export const zh = {
  title: '多集群总览',
  pollHint: '每 10 秒自动刷新（页面可见时）',
  emptyHint: '还没有配置任何 Kafka 集群',
  goAdd: '前往添加集群',
  stat: {
    partitions: '分区',
    consumerGroups: '消费组',
    offlinePartitions: 'Offline 分区',
    archivedMessages: '归档消息',
  },
  state: {
    online: '在线',
    connecting: '连接中',
  },
  logTotalEnterHint: '日志总量 {size} · 点击进入',
  metricsQueryFailed: '指标查询失败',
  clickStillEnter: '点击仍可进入该集群',
  connectingHint: '正在建立连接...',
  fetchFailed: '获取失败',
  clickBrowseArchive: '点击离线浏览历史消息',
  clickEnterCluster: '点击进入该集群',
  loadFailed: '加载多集群总览失败：{msg}',
}

export type Schema = typeof zh

export const en: Schema = {
  title: 'Multi-Cluster Overview',
  pollHint: 'Auto-refreshes every 10 seconds (when the page is visible)',
  emptyHint: 'No Kafka clusters configured yet',
  goAdd: 'Add a Cluster',
  stat: {
    partitions: 'Partitions',
    consumerGroups: 'Consumer Groups',
    offlinePartitions: 'Offline Partitions',
    archivedMessages: 'Archived Messages',
  },
  state: {
    online: 'Online',
    connecting: 'Connecting',
  },
  logTotalEnterHint: 'Total log size {size} · Click to enter',
  metricsQueryFailed: 'Failed to load metrics',
  clickStillEnter: 'Click to enter this cluster anyway',
  connectingHint: 'Connecting...',
  fetchFailed: 'Failed to fetch',
  clickBrowseArchive: 'Click to browse historical messages offline',
  clickEnterCluster: 'Click to enter this cluster',
  loadFailed: 'Failed to load multi-cluster overview: {msg}',
}
