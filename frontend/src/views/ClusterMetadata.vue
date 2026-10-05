<template>
  <div>
    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>
        <div class="card-header">
          <span style="font-weight: 600">{{ t('groupsMeta.metadata.title') }}</span>
          <el-tag v-if="clusterMode" size="small" type="info">
            {{ clusterMode }}
          </el-tag>
        </div>
      </template>
      <el-alert
        v-if="clusterMode === 'KRAFT'"
        type="info"
        :closable="false"
        show-icon
        :title="t('groupsMeta.metadata.kraftNotice')"
      />
      <el-alert
        v-else
        type="info"
        :closable="false"
        show-icon
        :title="t('groupsMeta.metadata.zkNotice')"
      />
    </el-card>

    <el-tabs v-model="activeTab">
      <!-- Brokers -->
      <el-tab-pane label="Brokers" name="brokers">
        <el-table
          :data="brokers"
          v-loading="loadingBrokers"
          stripe
          highlight-current-row
          @row-click="onBrokerClick"
        >
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="Host" min-width="140" prop="host" show-overflow-tooltip />
          <el-table-column prop="port" label="Port" width="100" />
          <el-table-column v-if="!isNarrow" label="Rack" min-width="100" prop="rack">
            <template #default="{ row }">{{ row.rack || '-' }}</template>
          </el-table-column>
        </el-table>

        <template v-if="selectedBrokerConfigs">
          <el-divider content-position="left">{{ t('groupsMeta.metadata.brokerConfigsDivider', {id: selectedBrokerConfigs.brokerId}) }}</el-divider>
          <el-descriptions :column="2" border>
            <el-descriptions-item
              v-for="(v, k) in selectedBrokerConfigs.configs"
              :key="k"
              :label="k"
            >{{ v }}</el-descriptions-item>
          </el-descriptions>
        </template>
      </el-tab-pane>

      <!-- Topics -->
      <el-tab-pane :label="t('groupsMeta.metadata.topicsTab')" name="topics">
        <el-form :inline="true" @submit.prevent>
          <el-form-item>
            <el-input
              v-model="topicQuery"
              :placeholder="t('groupsMeta.metadata.topicPlaceholder')"
              clearable
              @keyup.enter="loadTopicConfig"
            />
          </el-form-item>
          <el-form-item>
            <el-button :loading="loadingTopicConfig" type="primary" @click="loadTopicConfig">{{ t('common.search') }}</el-button>
          </el-form-item>
          <el-form-item>
            <el-button :disabled="!topicConfig" @click="showEditConfigs = true">{{ t('common.edit') }}</el-button>
          </el-form-item>
        </el-form>

        <el-descriptions v-if="topicConfig" :column="2" border style="margin-top: 16px">
          <el-descriptions-item v-for="(v, k) in topicConfig.configs" :key="k" :label="k">{{ v }}</el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>

      <!-- ACLs -->
      <el-tab-pane label="ACLs" name="acls">
        <el-table :data="acls" v-loading="loadingAcls" stripe>
          <el-table-column label="Principal" min-width="160" prop="principal" show-overflow-tooltip />
          <el-table-column v-if="!isNarrow" label="Host" min-width="120" prop="host" />
          <el-table-column label="Operation" min-width="120" prop="operation" />
          <el-table-column prop="permissionType" label="Permission" />
          <el-table-column prop="resourceType" label="Resource Type" />
          <el-table-column prop="resourceName" label="Resource Name" />
        </el-table>
        <el-empty v-if="!acls.length && !loadingAcls" :description="t('groupsMeta.metadata.noAcls')" />
      </el-tab-pane>

      <!-- Log Dirs -->
      <el-tab-pane label="Log Dirs" name="logdirs">
        <el-table v-loading="loadingLogDirs" :data="logDirSummaries" stripe>
          <el-table-column label="Broker" prop="brokerId" width="90" />
          <el-table-column label="Log Dir" min-width="220" prop="logDir" show-overflow-tooltip />
          <el-table-column label="Partitions" prop="partitionCount" width="110" />
          <el-table-column label="Total Size" width="130">
            <template #default="{ row }">{{ formatBytes(row.totalSize) }}</template>
          </el-table-column>
          <el-table-column v-if="!isNarrow" label="Error" min-width="160">
            <template #default="{ row }">
              <el-tag v-if="row.error" size="small" type="danger">{{ row.error }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!logDirSummaries.length && !loadingLogDirs" :description="t('groupsMeta.metadata.noLogDirs')" />

        <el-divider content-position="left">{{ t('groupsMeta.metadata.partitionDetailDivider') }}</el-divider>
        <el-form :inline="true" style="margin-bottom: 8px" @submit.prevent>
          <el-form-item :label="t('groupsMeta.metadata.topicFilter')">
            <el-input
              v-model="logDirTopic"
              clearable
              :placeholder="t('groupsMeta.metadata.topicFilterPlaceholder')"
              style="width: 220px"
              @change="resetLogDirDetail"
            />
          </el-form-item>
        </el-form>

        <el-collapse v-model="expandedLogDirs" @change="onLogDirsExpand">
          <el-collapse-item
            v-for="brokerId in brokerIdsWithLogDirs"
            :key="brokerId"
            :name="String(brokerId)"
          >
            <template #title>
              <span>Broker {{ brokerId }}</span>
            </template>
            <el-table
              v-loading="loadingLogDirDetail[brokerId]"
              :data="logDirDetail[brokerId] || []"
              size="small"
              stripe
            >
              <el-table-column label="Topic" min-width="180" prop="topic" show-overflow-tooltip />
              <el-table-column prop="partition" label="Partition" width="90" />
              <el-table-column label="Size" min-width="110">
                <template #default="{ row }">{{ formatBytes(row.size) }}</template>
              </el-table-column>
              <el-table-column v-if="!isNarrow" label="Future Replica" prop="future" width="120" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <el-empty v-if="!brokerIdsWithLogDirs.length && !loadingLogDirs" :description="t('groupsMeta.metadata.noBrokers')" />
    </el-tab-pane>
    </el-tabs>

    <EditTopicConfigsDialog
      v-model:visible="showEditConfigs"
      :cluster-id="activeClusterId ?? 0"
      :topic="topicQuery"
      @updated="loadTopicConfig"
    />
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {
  AclInfo,
  BrokerConfig,
  BrokerInfo,
  getAcls,
  getBrokerConfigs,
  getClusterInfo,
  getLogDirs,
  getLogDirsSummary,
  getTopicConfigs,
  LogDirSummary,
  PartitionLogInfo,
  TopicConfig,
} from '../api'
import {ElMessage} from 'element-plus'
import EditTopicConfigsDialog from '../components/EditTopicConfigsDialog.vue'
import {injectGlobalState} from '../composables/useGlobalState'
import {useResponsive} from '../composables/useResponsive'

// 双模模式感知：KRaft / ZooKeeper 显示对应副文案(计划 §4.2.5/§7.1)
const {clusterMode} = injectGlobalState()
const {t} = useI18n()
// 多集群:所有元数据端点挂集群段;窄档隐藏次要素养列
const {activeClusterId} = injectGlobalState()
const {isNarrow} = useResponsive()

const cid = computed(() => activeClusterId.value ?? -1)

// tab 深链：支持 /metadata?tab=logdirs 直达页签（仪表盘「日志总量」卡片跳转）。
// 非法值回退 brokers，避免 el-tabs 落在无任何激活页签的状态。
const route = useRoute()
const TAB_NAMES = ['brokers', 'topics', 'acls', 'logdirs'] as const
const activeTab = ref(
  TAB_NAMES.includes(route.query.tab as (typeof TAB_NAMES)[number])
    ? (route.query.tab as string)
    : 'brokers',
)

const showEditConfigs = ref(false)

const brokers = ref<BrokerInfo[]>([])
const loadingBrokers = ref(false)
const selectedBrokerConfigs = ref<BrokerConfig | null>(null)

const topicQuery = ref('')
const topicConfig = ref<TopicConfig | null>(null)
const loadingTopicConfig = ref(false)

const acls = ref<AclInfo[]>([])
const loadingAcls = ref(false)

// Log Dirs:默认只拉汇总(KB 级),默认全部折叠;展开某个 broker 才拉该 broker 的明细
const logDirSummaries = ref<LogDirSummary[]>([])
const loadingLogDirs = ref(false)
const expandedLogDirs = ref<string[]>([])
const logDirDetail = ref<Record<number, PartitionLogInfo[]>>({})
const loadingLogDirDetail = ref<Record<number, boolean>>({})
const logDirTopic = ref('')

const brokerIdsWithLogDirs = computed(() =>
  [...new Set(logDirSummaries.value.map((s) => s.brokerId))].sort((a, b) => a - b),
)

const loadBrokers = async () => {
  if (cid.value < 0) return
  loadingBrokers.value = true
  try {
    const res = await getClusterInfo(cid.value)
    brokers.value = res.data.brokers
  } catch (e: any) {
    ElMessage.error(t('groupsMeta.metadata.loadBrokersFailed', {msg: e.message}))
  } finally {
    loadingBrokers.value = false
  }
}

const onBrokerClick = async (row: BrokerInfo) => {
  try {
    const res = await getBrokerConfigs(cid.value, row.id)
    selectedBrokerConfigs.value = res.data
  } catch (e: any) {
    ElMessage.error(t('groupsMeta.metadata.loadBrokerConfigsFailed', {msg: e.message}))
  }
}

const loadTopicConfig = async () => {
  const topic = topicQuery.value.trim()
  if (!topic) return
  if (cid.value < 0) {
    ElMessage.warning(t('groupsMeta.groups.selectClusterFirst'))
    return
  }
  loadingTopicConfig.value = true
  try {
    const res = await getTopicConfigs(cid.value, topic)
    topicConfig.value = res.data
  } catch (e: any) {
    ElMessage.error(t('groupsMeta.metadata.loadTopicConfigsFailed', {msg: e.message}))
  } finally {
    loadingTopicConfig.value = false
  }
}

const loadAcls = async () => {
  if (cid.value < 0) return
  loadingAcls.value = true
  try {
    const res = await getAcls(cid.value)
    acls.value = res.data
  } catch (e: any) {
    ElMessage.error(t('groupsMeta.metadata.loadAclsFailed', {msg: e.message}))
  } finally {
    loadingAcls.value = false
  }
}

/** 默认视图:每 broker 每 log dir 一行汇总,不再拉全量分区明细。 */
const loadLogDirsSummary = async () => {
  if (cid.value < 0) return
  loadingLogDirs.value = true
  try {
    const res = await getLogDirsSummary(cid.value)
    logDirSummaries.value = res.data
  } catch (e: any) {
    ElMessage.error(t('groupsMeta.metadata.loadLogDirSummaryFailed', {msg: e.message}))
  } finally {
    loadingLogDirs.value = false
  }
}

const loadLogDirDetail = async (brokerId: number) => {
  if (cid.value < 0) return
  loadingLogDirDetail.value = {...loadingLogDirDetail.value, [brokerId]: true}
  try {
    const topic = logDirTopic.value.trim()
    // 传 brokerId 是唯一能真正减少 broker→客户端传输量的手段
    const res = await getLogDirs(cid.value, {brokerId, topic: topic || undefined})
    const entry = res.data.find((d) => d.brokerId === brokerId)
    logDirDetail.value = {...logDirDetail.value, [brokerId]: entry?.partitions ?? []}
  } catch (e: any) {
    ElMessage.error(t('groupsMeta.metadata.loadLogDirDetailFailed', {msg: e.message}))
  } finally {
    loadingLogDirDetail.value = {...loadingLogDirDetail.value, [brokerId]: false}
  }
}

/**
 * 展开 broker 时才拉明细;已加载过的 broker 不再重复请求。
 * 参数类型与 el-collapse 的 change 事件一致(string | number 的数组)。
 */
const onLogDirsExpand = async (names: (string | number)[]) => {
  const opened = names.map((n) => Number(n))
  await Promise.all(
    opened.map((brokerId) => (logDirDetail.value[brokerId] ? Promise.resolve() : loadLogDirDetail(brokerId))),
  )
}

/** 过滤条件变了,按旧条件拉到的明细必须失效(下次展开重新请求)。 */
const resetLogDirDetail = () => {
  logDirDetail.value = {}
}

const formatBytes = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(2) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

onMounted(() => {
  loadBrokers()
  loadAcls()
  loadLogDirsSummary()
})

// 切换激活集群:全部元数据重取,选中态/明细缓存一并失效
watch(cid, () => {
  brokers.value = []
  selectedBrokerConfigs.value = null
  topicConfig.value = null
  acls.value = []
  logDirSummaries.value = []
  logDirDetail.value = {}
  expandedLogDirs.value = []
  loadBrokers()
  loadAcls()
  loadLogDirsSummary()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
