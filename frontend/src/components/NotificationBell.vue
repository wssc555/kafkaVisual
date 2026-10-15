<template>
  <!--
    顶栏通知铃铛：徽标 + 点击展开通知面板。
    面板内容走 el-popover 的 default slot；铃铛本体放 #reference（EP 的触发锚点）。
    浮层 teleport 到 body，scoped 样式不生效 —— 宽度覆盖见下方非 scoped 样式块。
  -->
  <el-popover
    v-model:visible="panelOpen"
    :width="360"
    placement="bottom-end"
    popper-class="nb-popover"
    trigger="click"
  >
    <template #reference>
      <el-badge :hidden="unreadCount === 0" :max="99" :value="unreadCount" class="nb-bell">
        <el-button :aria-label="t('notification.ariaLabel')" text>
          <el-icon><BellIcon /></el-icon>
        </el-button>
      </el-badge>
    </template>

    <div class="nb-panel">
      <div class="nb-header">
        <span class="nb-header-title">{{ t('notification.title') }}</span>
        <span v-if="unreadCount > 0" class="nb-unread">
          {{ t('notification.unreadSuffix', { n: unreadCount }) }}
        </span>
        <div class="nb-header-actions">
          <el-button
            :disabled="!hasUnreadInList || ackingAll"
            :loading="ackingAll"
            size="small"
            text
            @click="onAckAll"
          >
            {{ t('notification.markAllRead') }}
          </el-button>
          <el-button
            :icon="RefreshIcon"
            :loading="listLoading"
            :title="t('notification.refresh')"
            size="small"
            text
            @click="loadList"
          />
        </div>
      </div>

      <div class="nb-list">
        <!-- 三态：首次加载 / 错误 / 空；错误态时 composable 保留 items，面板整体切到重试块 -->
        <div v-if="listLoading && items.length === 0" class="nb-state">
          <el-icon class="is-loading"><LoadingIcon /></el-icon>
          <span>{{ t('common.loading') }}</span>
        </div>
        <div v-else-if="listError" class="nb-state nb-state--error">
          <span>{{ t('notification.loadFailed') }}</span>
          <el-button size="small" text type="primary" @click="loadList">
            {{ t('notification.retry') }}
          </el-button>
        </div>
        <el-empty
          v-else-if="items.length === 0"
          :description="t('notification.empty')"
          :image-size="60"
        />
        <template v-else>
          <div v-for="n in items" :key="n.id" class="nb-item">
            <span v-if="!n.read" class="nb-dot" />
            <el-tag :type="severityTag(n.severity)" size="small">{{ n.severity }}</el-tag>
            <el-tag v-if="n.recovered" size="small" type="info">
              {{ t('notification.recovered') }}
            </el-tag>
            <span class="nb-item-title" @click="onOpen(n)">{{ n.title }}</span>
            <div class="nb-meta">
              {{ n.clusterName || '-' }} · {{ formatRelativeTime(n.firedAt) }}
            </div>
            <el-button
              :disabled="n.read || ackingIds.has(n.id)"
              size="small"
              text
              type="primary"
              @click="onAck(n)"
            >
              {{ t('notification.markRead') }}
            </el-button>
          </div>
        </template>
      </div>

      <div class="nb-footer">
        <el-button text type="primary" @click="onViewAll">
          {{ t('notification.viewAll') }}
        </el-button>
      </div>
    </div>
  </el-popover>
</template>

<script lang="ts" setup>
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {useRouter} from 'vue-router'
import {Bell as BellIcon, Loading as LoadingIcon, Refresh as RefreshIcon} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import {formatRelativeTime} from '../composables/useFormat'
import {injectNotifications} from '../composables/useNotifications'
import type {NotificationItem} from '../notifications/types'

const {t} = useI18n()
const router = useRouter()

// 全部状态与动作来自 composable：本组件零 API 直连
const {
  unreadCount,
  items,
  listLoading,
  listError,
  ackingIds,
  ackingAll,
  loadList,
  ackItem,
  ackAll,
} = injectNotifications()

const panelOpen = ref(false)

/** 「全部已读」作用于列表未读项：列表全已读时禁用，避免 total=0 的空操作提示 */
const hasUnreadInList = computed(() => items.value.some((n) => !n.read))

// 打开即懒加载；关闭不重拉（面板内已有内容时避免闪烁）
watch(panelOpen, (v) => {
  if (v) loadList()
})

/** 与 InspectionAlerts.vue 同款三档；3 行映射不值得抽公共模块 */
const severityTag = (severity?: string) => {
  if (severity === 'CRITICAL') return 'danger'
  if (severity === 'WARN') return 'warning'
  return 'info'
}

const onOpen = (n: NotificationItem) => {
  router.push({path: n.link.path, query: n.link.query})
  panelOpen.value = false
}

const onAck = async (n: NotificationItem) => {
  try {
    await ackItem(n)
  } catch (e: any) {
    ElMessage.error(t('notification.ackFailed', {msg: e?.message ?? ''}))
  }
}

const onAckAll = async () => {
  try {
    const r = await ackAll()
    if (r.fail > 0) {
      ElMessage.warning(t('notification.markAllDone', {ok: r.ok, total: r.total}))
    } else {
      ElMessage.success(t('notification.markAllDone', {ok: r.ok, total: r.total}))
    }
  } catch (e: any) {
    ElMessage.error(t('notification.ackFailed', {msg: e?.message ?? ''}))
  }
}

const onViewAll = () => {
  router.push('/inspection/alerts')
  panelOpen.value = false
}
</script>

<style scoped>
.nb-bell {
  margin-left: 12px;
}
.nb-panel {
  display: flex;
  flex-direction: column;
}
.nb-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.nb-header-title {
  font-weight: 600;
  font-size: 14px;
}
.nb-unread {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.nb-header-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
}
.nb-list {
  max-height: 380px;
  overflow-y: auto;
}
.nb-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 24px 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.nb-state--error {
  color: var(--el-color-danger);
}
.nb-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 13px;
}
.nb-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
  flex-shrink: 0;
}
/* 标题单行截断；禁 v-html（后端文案按纯文本渲染） */
.nb-item-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  cursor: pointer;
  color: var(--el-text-color-primary);
}
.nb-item-title:hover {
  color: var(--el-color-primary);
}
.nb-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.nb-footer {
  padding-top: 8px;
  text-align: right;
}
</style>

<!-- 非 scoped：popover 浮层 teleport 到 body，scoped 选择器命中不到 -->
<style>
.nb-popover {
  width: min(360px, calc(100vw - 32px)) !important;
}
</style>
