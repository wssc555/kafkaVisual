/**
 * 壳层文案：应用框架（App.vue 侧栏菜单/顶栏）、main.ts 启动错误弹框、
 * NotFound 404 页、FirstRunGuide 首次启动引导、StartupDiagnostics 面板静态文案。
 *
 * 注意：StartupDiagnostics 面板里的 bootLog 事件行为开发者诊断日志，保持中文不入包（评估文档 §2.2）。
 */
export const zh = {
  menu: {
    dashboard: '仪表盘',
    cluster: '集群',
    consumerGroups: '消费组',
    zookeeper: 'ZooKeeper',
    metadata: '元数据',
    clusterManage: '集群管理',
    settings: '设置',
  },
  header: {
    noCluster: '未选择集群',
    expandNav: '展开导航',
    collapseNav: '收起导航',
    openNav: '打开导航',
    language: '语言',
  },
  loadClustersFailed: '加载集群列表失败：{msg}',
  clusterModeFallback: '无法获取集群模式，已按 ZooKeeper 模式回退渲染；ZK 浏览器可能不可用',
  startup: {
    errorTitle: '启动错误',
    errorIntro: '应用启动过程中出现以下错误，后端服务可能不可用：',
    errorLogPath: '完整诊断日志：%APPDATA%\\com.kafkaviz.frontend\\logs\\backend.log',
    notReadyIn60s: '后端服务未在 60 秒内就绪。请查看诊断日志：%APPDATA%\\com.kafkaviz.frontend\\logs\\backend.log',
  },
  notFound: {
    description: '页面不存在',
    back: '返回仪表盘',
  },
  firstRun: {
    title: '欢迎使用 Kafka Visualizer',
    lead: '开始之前，请先添加您的第一个 Kafka 集群',
    text: '添加集群后即可浏览主题、消息、消费组、元数据等全部功能；未添加集群前，各功能页将保持空白。',
    hint: '填写连接信息后可先「测试连接」验证连通性，再保存并连接。',
    addCluster: '添加集群',
  },
  boot: {
    title: '正在启动后端服务…',
    backendSection: '后端日志（backend.log 尾部，实时刷新）',
    frontendSection: '前端事件',
    waitingJava: '(等待 java 进程输出…)',
    initializing: '(初始化…)',
    fullLog: '完整日志：%APPDATA%\\com.kafkaviz.frontend\\logs\\backend.log',
    tailOnly: '（仅显示尾部 {n} 行）',
  },
}

export type AppSchema = typeof zh

export const en: AppSchema = {
  menu: {
    dashboard: 'Dashboard',
    cluster: 'Clusters',
    consumerGroups: 'Consumer Groups',
    zookeeper: 'ZooKeeper',
    metadata: 'Metadata',
    clusterManage: 'Cluster Management',
    settings: 'Settings',
  },
  header: {
    noCluster: 'No cluster selected',
    expandNav: 'Expand navigation',
    collapseNav: 'Collapse navigation',
    openNav: 'Open navigation',
    language: 'Language',
  },
  loadClustersFailed: 'Failed to load cluster list: {msg}',
  clusterModeFallback: 'Unable to detect cluster mode; falling back to ZooKeeper rendering. The ZK browser may be unavailable.',
  startup: {
    errorTitle: 'Startup error',
    errorIntro: 'The following errors occurred during startup. The backend service may be unavailable:',
    errorLogPath: 'Full diagnostic log: %APPDATA%\\com.kafkaviz.frontend\\logs\\backend.log',
    notReadyIn60s: 'The backend service did not become ready within 60 seconds. Check the diagnostic log: %APPDATA%\\com.kafkaviz.frontend\\logs\\backend.log',
  },
  notFound: {
    description: 'Page not found',
    back: 'Back to dashboard',
  },
  firstRun: {
    title: 'Welcome to Kafka Visualizer',
    lead: 'Before you start, add your first Kafka cluster',
    text: 'Once a cluster is added, all features (topics, messages, consumer groups, metadata) become available; until then the feature pages stay empty.',
    hint: 'After filling in the connection details, use "Test connection" to verify connectivity before saving and connecting.',
    addCluster: 'Add cluster',
  },
  boot: {
    title: 'Starting backend service…',
    backendSection: 'Backend log (tail of backend.log, live)',
    frontendSection: 'Frontend events',
    waitingJava: '(waiting for java process output…)',
    initializing: '(initializing…)',
    fullLog: 'Full log: %APPDATA%\\com.kafkaviz.frontend\\logs\\backend.log',
    tailOnly: '(showing last {n} lines only)',
  },
}
