/**
 * 通用词条（跨页面复用的高频文案）。
 *
 * **本文件冻结维护**：各页面改造时只允许引用这里的
 * 既有 key；页面专属文案一律写入自己的域模块（dashboard.ts / topicDetail.ts / …），
 * 避免多人并行改造时在同一文件上产生合并冲突。
 */
export const zh = {
  confirm: '确认',
  cancel: '取消',
  retry: '重试',
  remove: '删除',
  edit: '编辑',
  save: '保存',
  search: '查询',
  refresh: '刷新',
  loading: '加载中…',
  operation: '操作',
  none: '无',
  unknownError: '未知错误',
  gotIt: '知道了',
  later: '稍后再说',
  backToCluster: '返回集群',
  confirmDeleteTitle: '确认删除',
  offline: '离线',
  no: '否',
  /** 写操作二次确认（useCrudConfirm） */
  crud: {
    confirmTitle: '确认操作',
  },
  /** offset 越界恢复引导（useOffsetRecovery） */
  offsetRecovery: {
    outOfRangeTitle: 'Offset 越界',
    outOfRangeMsg:
      '该 offset 已被 retention 清理或 compact 跳过，服务端告知此分区最早可用 offset 为 {earliest}。\n\n是否从 offset {earliest} 重新查询？',
    recoverBtn: '从 offset {earliest} 重查',
  },
}

export type CommonSchema = typeof zh

export const en: CommonSchema = {
  confirm: 'Confirm',
  cancel: 'Cancel',
  retry: 'Retry',
  remove: 'Delete',
  edit: 'Edit',
  save: 'Save',
  search: 'Query',
  refresh: 'Refresh',
  loading: 'Loading…',
  operation: 'Actions',
  none: 'None',
  unknownError: 'Unknown error',
  gotIt: 'Got it',
  later: 'Later',
  backToCluster: 'Back to clusters',
  confirmDeleteTitle: 'Confirm deletion',
  offline: 'Offline',
  no: 'No',
  crud: {
    confirmTitle: 'Confirm operation',
  },
  offsetRecovery: {
    outOfRangeTitle: 'Offset out of range',
    outOfRangeMsg:
      'This offset has been removed by retention or skipped by compaction. The server reports the earliest available offset for this partition is {earliest}.\n\nQuery again from offset {earliest}?',
    recoverBtn: 'Re-query from offset {earliest}',
  },
}
