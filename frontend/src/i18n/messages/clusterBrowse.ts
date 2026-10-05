/**
 * clusterBrowse 域文案：Cluster.vue / TopicList.vue / ClusterSwitcher.vue。
 * 通用词（common.*）不在此重复定义，组件内直接引用。
 */
export const zh = {
  page: {
    backToDashboard: '← 返回仪表盘',
    offlineAlertTitle: '集群离线 · 实时信息不可用',
    offlineAlertDesc:
      '无法获取集群实时信息与实时消息；下方 topic 列表来自本地归档台账，点击 topic 可浏览其历史消息。',
    clusterInfoTitle: '集群信息',
    loadClusterInfoFailed: '加载集群信息失败：{msg}',
  },
  topicList: {
    title: 'Topic 列表',
    newTopic: '新建 Topic',
    offlineAlertTitle: '集群离线 · 实时 topic 列表不可用',
    offlineAlertDesc:
      '以下为本地归档台账中的 topic（含已从 Kafka 删除的），点击可浏览其历史消息。',
    topicName: 'Topic 名称',
    deletedTag: '已删除 · 可查历史',
    archivedTag: '归档',
    viewHistory: '查历史',
    favoriteFailed: '收藏操作失败：{msg}',
    switchedToArchiveMode: '集群不可达，已切换为归档浏览模式',
    loadTopicsFailed: 'Failed to load topics: {msg}',
    loadTopicsFailedWithArchive: 'Failed to load topics: {msg}；归档台账同样失败：{msg2}',
    confirmDeleteTopic:
      '确认删除 Topic "{name}"？此操作不可逆，Kafka 不支持缩分区，已写入数据将永久丢失。',
    topicDeleted: 'Topic "{name}" deleted',
  },
  switcher: {
    selectCluster: '选择集群',
    manageClusters: '管理集群',
    addCluster: '添加集群',
    currentClusterAria: '当前集群 {name}，点击展开侧栏切换',
    online: '在线',
    connecting: '连接中',
  },
}

export type Schema = typeof zh

export const en: Schema = {
  page: {
    backToDashboard: '← Back to dashboard',
    offlineAlertTitle: 'Cluster offline · Live info unavailable',
    offlineAlertDesc:
      'Unable to fetch live cluster info or messages; the topic list below comes from the local archive ledger — click a topic to browse its message history.',
    clusterInfoTitle: 'Cluster Info',
    loadClusterInfoFailed: 'Failed to load cluster info: {msg}',
  },
  topicList: {
    title: 'Topics',
    newTopic: 'New Topic',
    offlineAlertTitle: 'Cluster offline · Live topic list unavailable',
    offlineAlertDesc:
      'The topics below come from the local archive ledger (including ones deleted from Kafka); click a topic to browse its message history.',
    topicName: 'Topic Name',
    deletedTag: 'Deleted · History available',
    archivedTag: 'Archived',
    viewHistory: 'History',
    favoriteFailed: 'Favorite operation failed: {msg}',
    switchedToArchiveMode: 'Cluster unreachable; switched to archive browsing mode',
    loadTopicsFailed: 'Failed to load topics: {msg}',
    loadTopicsFailedWithArchive: 'Failed to load topics: {msg}; archive ledger also failed: {msg2}',
    confirmDeleteTopic:
      'Are you sure you want to delete topic "{name}"? This operation is irreversible; Kafka does not support partition reduction, and written data will be permanently lost.',
    topicDeleted: 'Topic "{name}" deleted',
  },
  switcher: {
    selectCluster: 'Select cluster',
    manageClusters: 'Manage clusters',
    addCluster: 'Add cluster',
    currentClusterAria: 'Current cluster {name}, click to expand sidebar and switch',
    online: 'Online',
    connecting: 'Connecting',
  },
}
