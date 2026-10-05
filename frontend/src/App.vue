<template>
  <el-config-provider :locale="epLocale">
  <el-container style="height: 100vh">
    <!--
      壳层三档:
      wide(≥1440) 侧栏展开 220px / mid(1024–1439) 默认折叠 64px / narrow(<1024) 覆盖式抽屉。
      窄档不销毁侧栏 DOM,而是整体移出屏幕、经 .app-aside--drawer 覆盖在内容上
      (单份菜单标记,避免 el-drawer 与 el-aside 两处模板漂移)。
    -->
    <el-aside
      :class="{ 'app-aside--drawer': isNarrow, 'app-aside--open': isNarrow && drawerOpen }"
      :width="asideWidth"
      class="app-aside"
    >
      <div class="aside-inner">
        <ClusterSwitcher :collapsed="!isNarrow && collapsed" @expand="collapsed = false" />
        <el-menu
          ref="menuRef"
          :collapse="!isNarrow && collapsed"
          :default-active="currentRoute"
          class="aside-menu"
          router
        >
          <el-menu-item index="/">
            <el-icon><DataLine /></el-icon>
            <span>{{ t('app.menu.dashboard') }}</span>
          </el-menu-item>
          <!--
            「集群」下拉菜单(默认收起):展开后仅「消费组」一个子项。
            点击标题仍跳转 /cluster(原菜单项行为保留),展开/收起由 el-sub-menu 原生处理;
            /cluster 路由下标题手动高亮 —— 它不再是 menu-item,没有原生 is-active 态。
          -->
          <el-sub-menu index="cluster-nav">
            <template #title>
              <span
                :class="{ 'cluster-title--active': route.path === '/cluster' }"
                class="cluster-title"
                @click="goClusterPage"
              >
                <el-icon><Monitor /></el-icon>
                <span>{{ t('app.menu.cluster') }}</span>
              </span>
            </template>
            <el-menu-item index="/consumer-groups">
              <el-icon><User /></el-icon>
              <span>{{ t('app.menu.consumerGroups') }}</span>
            </el-menu-item>
          </el-sub-menu>
          <!-- ZK 模式专属入口；KRaft 模式下 /api/c/{id}/zk/** 返回 50302 -->
          <el-menu-item v-if="clusterMode === 'ZOOKEEPER'" index="/zk">
            <el-icon><Folder /></el-icon>
            <span>{{ t('app.menu.zookeeper') }}</span>
          </el-menu-item>
          <!-- 元数据浏览器双模可用（纯 AdminClient），常驻 -->
          <el-menu-item index="/metadata">
            <el-icon><Files /></el-icon>
            <span>{{ t('app.menu.metadata') }}</span>
          </el-menu-item>
          <el-menu-item index="/clusters">
            <el-icon><Setting /></el-icon>
            <span>{{ t('app.menu.clusterManage') }}</span>
          </el-menu-item>
          <el-menu-item index="/settings">
            <el-icon><Tools /></el-icon>
            <span>{{ t('app.menu.settings') }}</span>
          </el-menu-item>
        </el-menu>
        <!-- 收起/展开导航：仅宽/中档显示(窄档抽屉由遮罩/汉堡键收起)；折叠态仅图标 -->
        <div v-if="!isNarrow" class="aside-footer">
        <el-button
          :aria-label="collapsed ? t('app.header.expandNav') : t('app.header.collapseNav')"
          :icon="collapsed ? Expand : Fold"
          :title="collapsed ? t('app.header.expandNav') : t('app.header.collapseNav')"
            style="width: 100%"
            text
            @click="collapsed = !collapsed"
          />
        </div>
      </div>
    </el-aside>

    <el-container>
      <el-header class="app-header">
        <!-- 窄档汉堡键:唤出覆盖抽屉 -->
        <el-button
          v-if="isNarrow"
          :aria-label="t('app.header.openNav')"
          :icon="MenuIcon"
          class="hamburger"
          text
          @click="drawerOpen = true"
        />
        <h2 class="app-title">Kafka Visualizer</h2>
        <el-tag v-if="clusterMode" size="small" type="info" style="margin-left: 12px">
          {{ clusterMode }}
        </el-tag>
        <span v-if="activeCluster" class="active-cluster">
          <span :style="{ background: activeDotColor }" class="state-dot" />
          {{ activeCluster.name }}
        </span>
        <span v-else-if="clustersLoaded" class="active-cluster active-cluster--none">
          {{ t('app.header.noCluster') }}
        </span>
        <!-- 语言切换:下拉即切,localStorage 持久化 -->
        <el-dropdown class="lang-switch" trigger="click" @command="onLanguageCommand">
          <el-button :aria-label="t('app.header.language')" text>
            {{ currentLanguageLabel }}
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="lang in LANGUAGES"
                :key="lang.value"
                :command="lang.value"
              >
                {{ lang.label }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main>
        <!-- 握手等待期(booting)不渲染任何页面组件:避免 baseURL 未定时发起请求风暴 -->
        <router-view v-if="bootStatus.ready" />
      </el-main>
    </el-container>

    <!-- 窄档抽屉遮罩:点击关闭(路由切换也会自动收起) -->
    <div v-if="isNarrow && drawerOpen" class="aside-mask" @click="drawerOpen = false" />

    <!-- 启动诊断面板:握手等待期全屏显示后端日志流 + 前端事件,结束后自动卸载 -->
    <StartupDiagnostics v-if="!bootStatus.ready" />

    <!-- 首次启动引导:零集群时弹出,引导进入集群配置 -->
    <FirstRunGuide
      v-model="firstRunGuideVisible"
      @add="handleGuideAdd"
      @later="handleGuideLater"
    />
  </el-container>
  </el-config-provider>
</template>

<script setup lang="ts">
import {computed, onBeforeUnmount, onMounted, ref, watch, watchEffect} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {
  DataLine,
  Expand,
  Files,
  Fold,
  Folder,
  Menu as MenuIcon,
  Monitor,
  Setting,
  Tools,
  User,
} from '@element-plus/icons-vue'
import type {MenuInstance} from 'element-plus'
import {ElMessage} from 'element-plus'
import ClusterSwitcher from './components/ClusterSwitcher.vue'
import FirstRunGuide from './components/FirstRunGuide.vue'
import StartupDiagnostics from './components/StartupDiagnostics.vue'
import {bootReady, bootStatus} from './composables/bootLog'
import {PREF_KEYS, useGlobalState} from './composables/useGlobalState'
import {useResponsive} from './composables/useResponsive'
import {currentLocale, epLocale, LANGUAGES, type LocaleId, setLanguage} from './i18n'
import './styles/responsive.css'

const route = useRoute()
const router = useRouter()
const {t} = useI18n()
const currentRoute = computed(() => route.path)

// ---- 语言切换 ----
const currentLanguageLabel = computed(
  () => LANGUAGES.find((l) => l.value === currentLocale.value)?.label ?? '',
)
const onLanguageCommand = (cmd: string | number | object) => setLanguage(cmd as LocaleId)

const {tier, isNarrow} = useResponsive()

/** el-menu 实例:进入 /consumer-groups 时手动展开「集群」下拉(EP 2.14 路由切换不自动展开父级) */
const menuRef = ref<MenuInstance | null>(null)

/**
 * 点击「集群」下拉标题:仍进入集群(Topic 浏览)页。
 * 展开/收起由 el-sub-menu 原生处理(点击标题原生就会切换)。
 */
const goClusterPage = () => {
  router.push('/cluster')
}

// 路由切到 /consumer-groups 时展开下拉,保证「消费组」高亮子项可见;
// /cluster 不展开 —— 下拉默认收起。EP 2.14 的 default-active 变化只更新高亮、不动 openedMenus。
watch(currentRoute, (p) => {
  if (p === '/consumer-groups') menuRef.value?.open('cluster-nav')
})

// 全局单例：由 App 创建并 provide，其余组件通过 injectGlobalState() 读取
const {
  clusterMode,
  clusters,
  clustersLoaded,
  activeCluster,
  activeClusterId,
  loadClusters,
  refreshStatuses,
  setActiveCluster,
  loadClusterMode,
  loadPreferences,
  savePreference,
  preferences,
} = useGlobalState()

/** 侧边导航折叠态：true = 仅图标（宽/中档有效；窄档走抽屉，不折叠） */
const collapsed = ref(false)
/** 窄档覆盖抽屉开关 */
const drawerOpen = ref(false)
/** 偏好恢复完成前不回写 asideCollapsed,避免启动时一次无意义的 PUT */
let preferenceRestored = false

const asideWidth = computed(() => {
  if (isNarrow.value) return '220px'
  return collapsed.value ? '64px' : '220px'
})

const activeDotColor = computed(() => {
  const cl = activeCluster.value
  if (!cl) return 'var(--el-text-color-disabled)'
  if (cl.displayState === 'ONLINE') return 'var(--el-color-success)'
  if (cl.displayState === 'CONNECTING') return 'var(--el-color-warning)'
  return cl.errorSummary ? 'var(--el-color-danger)' : 'var(--el-text-color-disabled)'
})

// ---- 启动时序：
// loadClusters → 恢复 activeClusterId 偏好（loadPreferences 内）→ 折叠态偏好 → mode 加载 ----
onMounted(async () => {
  // 等待 sidecar 握手结束(成功或超时)再走启动序列:
  // booting 期间 baseURL 未定,提前 loadClusters 只会产生一批注定失败的请求。
  await bootReady

  try {
    await loadClusters()
  } catch (e: any) {
    ElMessage.error(t('app.loadClustersFailed', {msg: e.message}))
  }

  try {
    await loadPreferences()
  } catch {
    // 偏好拉取失败不阻断启动:本地默认值可用
  }

  // 折叠态偏好:显式设置过才生效;未设置时 mid 档默认折叠
  const savedCollapsed = preferences.value[PREF_KEYS.asideCollapsed]
  if (savedCollapsed === '1') collapsed.value = true
  else if (savedCollapsed === '0') collapsed.value = false
  else collapsed.value = tier.value === 'mid'
  preferenceRestored = true

  // 首次启动且没有任何已保存的激活集群 → 自动选第一个,避免功能页全部空转
  ensureActiveCluster()

  // 首次启动引导:集群列表为空 = 从未添加过集群 → 弹欢迎引导。
  // 注意只在 loadClusters 成功后判断 —— 加载失败时无法区分「零集群」与「后端不可达」,
  // 此时引导弹框没有意义(后端不可达的错误由 main.ts 的启动错误弹框负责)。
  if (clusters.value.length === 0 && !firstRunGuideDismissed.value) {
    firstRunGuideVisible.value = true
  }
})

// ---- 首次启动引导 ----
const firstRunGuideVisible = ref(false)
/** 本次会话内用户已明确处理过引导(稍后再说/已跳转),不再重复弹出 */
const firstRunGuideDismissed = ref(false)

const handleGuideAdd = () => {
  firstRunGuideVisible.value = false
  firstRunGuideDismissed.value = true
  router.push({path: '/clusters', query: {new: '1'}})
}

const handleGuideLater = () => {
  firstRunGuideVisible.value = false
  firstRunGuideDismissed.value = true
  // 留在当前页;零集群重定向会把用户带到集群管理页,从那里也能随时添加
}

/** 折叠态持久化(防抖 500ms 落 ui_preference) */
watch(collapsed, (v) => {
  if (preferenceRestored) savePreference(PREF_KEYS.asideCollapsed, v ? '1' : '0')
})

/**
 * 激活集群兜底:
 * - 未选择时选第一个;
 * - 激活集群被删除后自动落到第一个(集群 id 不复用,留着只会全页 40404)。
 * 零集群时什么都不做 —— 零集群重定向在下方 watchEffect。
 */
const ensureActiveCluster = () => {
  if (!clustersLoaded.value || clusters.value.length === 0) return
  const current = activeClusterId.value
  if (current == null || !clusters.value.some((c) => c.id === current)) {
    setActiveCluster(clusters.value[0].id)
  }
}

watch(clusters, ensureActiveCluster)

// 模式跟随激活集群:切换后重取,驱动 ZK 菜单项显隐
watch(activeClusterId, async (id) => {
  if (id == null) return
  try {
    await loadClusterMode(id)
  } catch {
    // 离线集群拉不到模式是预期内状态(切换器圆点已表达),不再叠加警告;
    // 其余失败(在线但瞬时错误)保持旧行为:回退默认并提示一次
    const cl = clusters.value.find((c) => c.id === id)
    if (!cl || cl.displayState !== 'OFFLINE') {
      ElMessage.warning(t('app.clusterModeFallback'))
    }
  }
}, {immediate: true})

// 零集群引导:全部功能页重定向到集群管理
watchEffect(() => {
  if (clustersLoaded.value && clusters.value.length === 0 && route.path !== '/clusters') {
    router.replace('/clusters')
  }
})

// 窄档路由切换后自动收起抽屉
watch(() => route.path, () => {
  drawerOpen.value = false
})

// ---- 集群状态轻量轮询(10s,页面可见时):驱动切换器/仪表盘的状态圆点 ----
let statusTimer: number | null = null

const startStatusPolling = () => {
  if (statusTimer !== null) return
  statusTimer = window.setInterval(() => {
    if (document.hidden || clusters.value.length === 0) return
    refreshStatuses().catch(() => {
      // 状态轮询失败静默:圆点维持上次状态,下一轮再试
    })
  }, 10000)
}

// 浏览器页签隐藏时暂停,回来立即刷一次(与 TopicDetail 实时轮询同款纪律)
const onVisibilityChange = () => {
  if (!document.hidden && clusters.value.length > 0) {
    refreshStatuses().catch(() => {})
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  startStatusPolling()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (statusTimer !== null) {
    clearInterval(statusTimer)
    statusTimer = null
  }
})
</script>

<style>
body {
  margin: 0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}
.el-header h2 {
  color: #303133;
}
/* 侧边栏宽度切换动画（150-300ms 微交互区间） */
.app-aside {
  transition: width 0.25s ease;
  overflow: hidden;
}
.aside-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.aside-menu {
  flex: 1;
}
.aside-footer {
  border-top: 1px solid var(--el-menu-border-color, #e6e6e6);
  padding: 6px;
}

/* ---- 窄档覆盖式抽屉:侧栏整体滑入覆盖,而不是挤压内容 ---- */
@media (max-width: 1023.98px) {
  .app-aside--drawer {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 1200;
    /* 覆盖模式下不做 width 过渡(display 切换语义),改做滑入位移 */
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: var(--el-box-shadow-light);
  }
  .app-aside--drawer.app-aside--open {
    transform: translateX(0);
  }
}
.aside-mask {
  position: fixed;
  inset: 0;
  z-index: 1100;
  background: rgba(0, 0, 0, 0.35);
}
</style>

<style scoped>
.app-header {
  border-bottom: 1px solid #dcdfe6;
  display: flex;
  align-items: center;
  padding: 0 20px;
}
/* 窄档标题缩略 */
.app-title {
  margin: 0;
  font-size: 18px;
}
.app-header .hamburger + .app-title {
  font-size: 16px;
}
.active-cluster {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.active-cluster--none {
  color: var(--el-text-color-placeholder);
}
/* 语言切换按钮(紧随集群状态之后,顶栏最右) */
.lang-switch {
  margin-left: 12px;
  font-size: 13px;
}
.state-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
/* 「集群」下拉标题:/cluster 路由下按菜单激活色高亮(标题不是 menu-item,无原生 is-active) */
.cluster-title {
  display: inline-flex;
  align-items: center;
}
.cluster-title--active {
  color: var(--el-menu-active-color, var(--el-color-primary));
}
</style>
