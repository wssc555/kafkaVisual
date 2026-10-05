import {createRouter, createWebHistory} from 'vue-router'
import {injectGlobalState} from '../composables/useGlobalState'

// 懒加载：首屏只加载仪表盘，其余视图按需拆包（TopicDetail / ZkBrowser 交互最重，收益最大）
const Dashboard = () => import('../views/Dashboard.vue')
const Cluster = () => import('../views/Cluster.vue')
const TopicDetail = () => import('../views/TopicDetail.vue')
const ConsumerGroup = () => import('../views/ConsumerGroup.vue')
const ZkBrowser = () => import('../views/ZkBrowser.vue')
const ClusterMetadata = () => import('../views/ClusterMetadata.vue')
const ClusterManage = () => import('../views/ClusterManage.vue')
const Settings = () => import('../views/Settings.vue')

const routes = [
  { path: '/', name: 'Dashboard', component: Dashboard },
  { path: '/cluster', name: 'Cluster', component: Cluster },
  { path: '/topics/:name', name: 'TopicDetail', component: TopicDetail },
  { path: '/consumer-groups', name: 'ConsumerGroup', component: ConsumerGroup },
  { path: '/zk', name: 'ZkBrowser', component: ZkBrowser },
  { path: '/metadata', name: 'ClusterMetadata', component: ClusterMetadata },
  // 集群注册表管理(增删改/连接断开);零集群时是全部路由的重定向落点
  { path: '/clusters', name: 'ClusterManage', component: ClusterManage },
  // 数据源设置(SQLite/PostgreSQL/MySQL)与偏好
  { path: '/settings', name: 'Settings', component: Settings },
  // 未匹配 URL 不再渲染空白页，给出明确的 404 引导
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('../views/NotFound.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * /zk 是 ZooKeeper 模式专属页。KRaft 集群下 ZkController 因 @Conditional 不注册，
 * /api/zk/** 返回 404；若用户手输或通过历史 URL 直达 /zk，ZkBrowser 会立即打 ZK 接口，
 * 得到不可解释的 404 toast + 空页面。这里做模式守卫，直接引导到元数据浏览器。
 *
 * 首帧 mode 未加载时（modeLoaded=false）放行：此刻集群模式尚不可知，硬拦会误伤
 * 正在加载中的 ZK 集群。放行后的收尾交给 ZkBrowser —— 它通过 injectGlobalState
 * 自检，模式确认为 KRaft 时不发那个必然 404 的请求，而是展示跳转引导。
 */
router.beforeEach((to, _from, next) => {
  if (to.path === '/zk') {
    const state = injectGlobalState()
    if (state.modeLoaded.value && state.clusterMode.value === 'KRAFT') {
      next('/metadata')
      return
    }
  }
  next()
})

export default router