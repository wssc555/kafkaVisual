<template>
  <div :class="{ 'is-collapsed': collapsed }" class="cluster-switcher">
    <!--
      展开态:集群下拉 + 状态圆点。
      折叠态(aside 64px):退化为图标按钮,点击展开侧栏再选(64px 放不下下拉面板)。
    -->
    <template v-if="!collapsed">
      <div v-if="clusters.length > 0" class="switcher-select">
        <el-select
          :model-value="activeClusterId ?? undefined"
          :placeholder="t('clusterBrowse.switcher.selectCluster')"
          size="small"
          @change="onSelect"
        >
          <el-option
            v-for="cl in clusters"
            :key="cl.id"
            :label="cl.name"
            :value="cl.id"
          >
            <span class="switcher-option">
              <el-tooltip
                :content="cl.errorSummary || ''"
                :disabled="!cl.errorSummary"
                placement="right"
              >
                <span :style="{ background: dotColor(cl) }" class="state-dot" />
              </el-tooltip>
              <span class="option-name">{{ cl.name }}</span>
              <span :class="`state-${cl.displayState.toLowerCase()}`" class="option-state">
                {{ stateText(cl.displayState) }}
              </span>
            </span>
          </el-option>
        </el-select>
        <el-button
          :icon="Setting"
          class="manage-btn"
          size="small"
          text
          @click="goManage"
        >
          {{ t('clusterBrowse.switcher.manageClusters') }}
        </el-button>
      </div>

      <!-- 零集群引导:不显示下拉,直接给"添加集群"入口;首拉未完成时先给加载态 -->
      <div v-else-if="!clustersLoaded" class="switcher-empty">
        <span class="loading-hint">{{ t('common.loading') }}</span>
      </div>
      <div v-else class="switcher-empty">
        <el-button size="small" type="primary" @click="goManage">{{ t('clusterBrowse.switcher.addCluster') }}</el-button>
      </div>
    </template>

    <!-- 折叠态:当前集群状态色圆点 + 点击展开侧栏 -->
    <el-tooltip
      v-else
      :content="activeCluster ? activeCluster.name : t('clusterBrowse.switcher.selectCluster')"
      placement="right"
    >
      <button
        :aria-label="activeCluster ? t('clusterBrowse.switcher.currentClusterAria', {name: activeCluster.name}) : t('clusterBrowse.switcher.selectCluster')"
        class="collapsed-btn"
        type="button"
        @click="$emit('expand')"
      >
        <span :style="{ background: dotColor(activeCluster) }" class="state-dot" />
        <el-icon><Connection /></el-icon>
      </button>
    </el-tooltip>
  </div>
</template>

<script lang="ts" setup>
import {useRouter} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {Connection, Setting} from '@element-plus/icons-vue'
import type {ClusterDisplayState, ClusterSummary} from '../api'
import {injectGlobalState} from '../composables/useGlobalState'

const {t} = useI18n()

/**
 * 侧栏顶部集群切换器。
 * 每项带在线/离线状态圆点:绿 ONLINE / 黄 CONNECTING / 红 OFFLINE+错误摘要 / 灰 OFFLINE。
 * 切换只更新全局 activeClusterId,当前路由不变,各视图 watch 后自行重取。
 */
withDefaults(defineProps<{ collapsed?: boolean }>(), { collapsed: false })

defineEmits<{ expand: [] }>()

const router = useRouter()
const {clusters, clustersLoaded, activeClusterId, activeCluster, setActiveCluster} = injectGlobalState()

const onSelect = (id: number) => {
  if (id != null) setActiveCluster(id)
}

const goManage = () => {
  router.push('/clusters')
}

/** 状态圆点配色;OFFLINE + errorSummary 用红色(有故障信息),否则灰色(纯离线) */
const dotColor = (cl?: ClusterSummary | null) => {
  if (!cl) return 'var(--el-text-color-disabled)'
  switch (cl.displayState as ClusterDisplayState) {
    case 'ONLINE':
      return 'var(--el-color-success)'
    case 'CONNECTING':
      return 'var(--el-color-warning)'
    default:
      return cl.errorSummary ? 'var(--el-color-danger)' : 'var(--el-text-color-disabled)'
  }
}

const stateText = (s: ClusterDisplayState) =>
  s === 'ONLINE'
    ? t('clusterBrowse.switcher.online')
    : s === 'CONNECTING'
      ? t('clusterBrowse.switcher.connecting')
      : t('common.offline')
</script>

<style scoped>
.cluster-switcher {
  padding: 10px 10px 8px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.switcher-select {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.switcher-select :deep(.el-select) {
  width: 100%;
}
.manage-btn {
  width: 100%;
  justify-content: flex-start;
  padding-left: 4px;
}
.switcher-empty {
  text-align: center;
}
.loading-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.switcher-option {
  display: flex;
  align-items: center;
  gap: 8px;
}
.option-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}
.option-state {
  font-size: 12px;
}
.state-online {
  color: var(--el-color-success);
}
.state-connecting {
  color: var(--el-color-warning);
}
.state-offline {
  color: var(--el-text-color-secondary);
}
/* 状态圆点:12px 圆,状态色由内联 style 提供 */
.state-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
/* 折叠态图标按钮:与 el-menu 折叠项的尺寸观感对齐 */
.collapsed-btn {
  width: 100%;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: none;
  background: transparent;
  color: var(--el-text-color-regular);
  cursor: pointer;
  border-radius: 4px;
}
.collapsed-btn:hover {
  background: var(--el-fill-color);
}
</style>
