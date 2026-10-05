<template>
  <div>
    <!--
      KRaft 模式下后端 ZkController 因 @Conditional(ZkModeCondition) 整个不注册，
      /api/zk/** 必然 404。这里直接给引导而不是发一个注定失败的请求。
    -->
    <el-card v-if="isKraft" shadow="never">
      <el-empty :description="t('groupsMeta.zk.kraftEmpty')">
        <el-button size="small" type="primary" @click="$router.push('/metadata')">
          {{ t('groupsMeta.zk.goMetadata') }}
        </el-button>
      </el-empty>
    </el-card>

    <template v-else>
      <el-card shadow="never" style="margin-bottom: 16px">
        <template #header><span style="font-weight: 600">{{ t('groupsMeta.zk.title') }}</span></template>

        <el-form :inline="true" @submit.prevent>
          <el-form-item :label="t('groupsMeta.zk.pathLabel')">
            <el-input
              v-model="path"
              clearable
              :placeholder="t('groupsMeta.zk.pathPlaceholder')"
              style="width: 300px"
              @keyup.enter="loadChildren"
            />
          </el-form-item>
          <el-form-item>
            <el-checkbox v-model="recursive">{{ t('groupsMeta.zk.recursive') }}</el-checkbox>
          </el-form-item>
          <el-form-item>
            <el-button :loading="loading" type="primary" @click="loadChildren">
              {{ t('common.search') }}
            </el-button>
          </el-form-item>
        </el-form>

        <div class="quick-paths">
          <span class="quick-label">{{ t('groupsMeta.zk.quickPathsLabel') }}</span>
          <el-button
            v-for="p in quickPaths"
            :key="p"
            link
            size="small"
            type="primary"
            @click="useQuickPath(p)"
          >
            {{ p }}
          </el-button>
        </div>
      </el-card>

      <el-card v-if="result" shadow="never">
        <template #header>
          <div class="card-header">
            <span style="font-weight: 600">{{ result.path }}</span>
            <el-tag v-if="result.stat" size="small" type="info">
              {{ t('groupsMeta.zk.childrenCount', {n: result.stat.numChildren}) }}
            </el-tag>
          </div>
        </template>

        <!--
          truncated 只在递归超限时为 true（仍是 200 / code=0），未截断时后端显式输出
          null —— 所以必须用 `=== true` 判定，否则用户会无感知地看到不完整的子树。
        -->
        <el-alert
          v-if="result.truncated === true"
          :closable="false"
          :description="t('groupsMeta.zk.truncatedDesc')"
          show-icon
          style="margin-bottom: 12px"
          :title="t('groupsMeta.zk.truncatedTitle')"
          type="warning"
        />

        <el-descriptions v-if="result.stat" :column="3" border size="small" style="margin-bottom: 16px">
          <el-descriptions-item label="czxid">{{ result.stat.czxid }}</el-descriptions-item>
          <el-descriptions-item label="mzxid">{{ result.stat.mzxid }}</el-descriptions-item>
          <el-descriptions-item label="version">{{ result.stat.version }}</el-descriptions-item>
          <el-descriptions-item label="cversion">{{ result.stat.cversion }}</el-descriptions-item>
          <el-descriptions-item label="aversion">{{ result.stat.aversion }}</el-descriptions-item>
          <el-descriptions-item label="dataLength">{{ result.stat.dataLength }}</el-descriptions-item>
          <el-descriptions-item label="numChildren">{{ result.stat.numChildren }}</el-descriptions-item>
          <el-descriptions-item label="ephemeralOwner">
            {{ result.stat.ephemeralOwner }}
          </el-descriptions-item>
          <el-descriptions-item label="pzxid">{{ result.stat.pzxid }}</el-descriptions-item>
          <el-descriptions-item label="ctime">{{ formatTimestamp(result.stat.ctime) }}</el-descriptions-item>
          <el-descriptions-item label="mtime">{{ formatTimestamp(result.stat.mtime) }}</el-descriptions-item>
        </el-descriptions>

        <el-table :data="result.children || []" size="small" stripe>
          <el-table-column :label="t('groupsMeta.zk.colPath')" min-width="280" prop="path" show-overflow-tooltip />
          <el-table-column :label="t('groupsMeta.zk.colChildren')" width="100">
            <template #default="{ row }">{{ row.stat?.numChildren ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="dataLength" width="110">
            <template #default="{ row }">{{ row.stat?.dataLength ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="version" width="90">
            <template #default="{ row }">{{ row.stat?.version ?? '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('common.operation')" align="center" width="140">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="useQuickPath(row.path)">
                {{ t('groupsMeta.zk.view') }}
              </el-button>
              <el-button
                :disabled="row.path === '/'"
                link
                size="small"
                type="danger"
                @click="handleDelete(row.path)"
              >
                {{ t('common.remove') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-empty v-if="!result.children?.length" :description="t('groupsMeta.zk.noChildren')" />
      </el-card>

      <el-card v-else-if="!loading" shadow="never">
        <el-empty :description="t('groupsMeta.zk.emptyHint')" />
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {deleteZkNode, listZkChildren, type ZkChildrenResult} from '../api'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import {formatTimestamp} from '../composables/useFormat'
import {injectGlobalState} from '../composables/useGlobalState'
import {ElMessage} from 'element-plus'

const {confirm} = useCrudConfirm()
const {t} = useI18n()
const {clusterMode, modeLoaded, activeClusterId} = injectGlobalState()

const path = ref('/brokers')
const recursive = ref(false)
const loading = ref(false)
const result = ref<ZkChildrenResult | null>(null)

/** 模式已确认且为 KRaft —— 此时 /api/zk/** 必然 404，不发起请求 */
const isKraft = computed(() => modeLoaded.value && clusterMode.value === 'KRAFT')

const quickPaths = [
  '/',
  '/brokers',
  '/brokers/ids',
  '/brokers/topics',
  '/consumers',
  '/config',
  '/controller',
]

const useQuickPath = (p: string) => {
  path.value = p
  loadChildren()
}

const loadChildren = async () => {
  if (activeClusterId.value == null) {
    ElMessage.warning(t('groupsMeta.zk.selectClusterFirst'))
    return
  }
  const p = path.value?.trim()
  if (!p) {
    ElMessage.warning(t('groupsMeta.zk.inputPath'))
    return
  }

  loading.value = true
  result.value = null
  try {
    const res = await listZkChildren(activeClusterId.value, p, recursive.value)
    result.value = res.data
  } catch (e: any) {
    // 无 ZK 集群(KRaft) 50302 / ZK 异常 50000,直接展示 msg
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/**
 * 模式未确认（首帧）时不自动请求：路由守卫在 modeLoaded=false 时放行 /zk，
 * 而此刻集群是不是 KRaft 尚不可知，KRaft 下打过去只会拿到不可解释的 404。
 * 等模式确认后再决定是否拉取；若模式始终拉不到，页面停在
 * 「输入 ZK 路径后点击查询」的空态，用户仍可手动查询（loadChildren 不受此门控）。
 */
watch(
  () => modeLoaded.value,
  (loaded) => {
    if (!loaded) return
    if (isKraft.value) {
      result.value = null
      return
    }
    loadChildren()
  },
  {immediate: true},
)

/**
 * 切换激活集群后重取:两个集群都是 ZK 模式时 modeLoaded 保持 true,
 * 不会触发上面的 watch,必须单独监听集群切换。
 */
watch(activeClusterId, (id) => {
  if (id == null) {
    result.value = null
    return
  }
  result.value = null
  if (!isKraft.value) {
    loadChildren()
  }
})

const handleDelete = async (nodePath: string) => {
  if (activeClusterId.value == null) {
    ElMessage.warning(t('groupsMeta.zk.selectClusterFirst'))
    return
  }
  if (nodePath === '/') {
    ElMessage.warning(t('groupsMeta.zk.rootNoDelete'))
    return
  }

  const ok = await confirm(
    t('groupsMeta.zk.confirmDeleteMsg', {path: nodePath}),
    t('groupsMeta.zk.confirmDeleteTitle'),
    'error',
  )
  if (!ok) return

  try {
    await deleteZkNode(activeClusterId.value, nodePath)
    ElMessage.success(t('groupsMeta.zk.deleted', {path: nodePath}))
    await loadChildren()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.quick-paths {
  margin-top: 4px;
}
.quick-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-right: 4px;
}
</style>
