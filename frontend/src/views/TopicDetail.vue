<template>
  <div>
    <!--
      el-page-header 的返回箭头：Element Plus 始终渲染该箭头，必须绑 @back 才有点击行为。
      运维操作收进 #extra 槽；加载失败时隐藏运维入口；离线集群禁用全部 Kafka 写操作。
    -->
    <el-page-header
      :title="topicName"
      :content="t('topicDetail.page.content')"
      style="margin-bottom: 16px"
      @back="$router.push('/cluster')"
    >
      <template #extra>
        <div v-if="!loadFailed">
          <el-button :disabled="isOffline" size="small" @click="showEditConfigs = true">{{ t('topicDetail.page.editConfigs') }}</el-button>
          <el-button :disabled="isOffline" size="small" @click="showExpand = true">{{ t('topicDetail.page.expand') }}</el-button>
          <el-button :disabled="isOffline" size="small" type="primary" @click="showProduce = true">
            {{ t('topicDetail.page.produce') }}
          </el-button>
        </div>
      </template>
    </el-page-header>

    <!-- 离线横幅:实时入口禁用,自动展示归档浏览 -->
    <el-alert
      v-if="isOffline"
      :closable="false"
      :description="t('topicDetail.page.offlineDesc')"
      show-icon
      style="margin-bottom: 16px"
      :title="t('topicDetail.page.offlineTitle')"
      type="warning"
    />

    <!--
      加载失败时展示恢复入口，而不是空白页。
      50302（离线/未连接）不进入失败接管:归档 tab 仍可用,由离线横幅接管页面语义。
      40401 = Topic 不存在：重试必然再失败，主操作改为「返回集群」。
    -->
    <el-card v-if="loadFailed" shadow="never">
      <el-empty :description="loadFailDescription">
        <el-button
          :loading="loadingTopic"
          :type="isTopicMissing ? 'default' : 'primary'"
          size="small"
          @click="loadTopic"
        >
          {{ t('common.retry') }}
        </el-button>
        <el-button
          :type="isTopicMissing ? 'primary' : 'default'"
          size="small"
          @click="$router.push('/cluster')"
        >
          {{ t('common.backToCluster') }}
        </el-button>
      </el-empty>
    </el-card>

    <template v-else>
      <el-tabs v-model="activeTab">
        <!-- name 必须显式给出且与 activeTab 值对应：不设 name 时 EP 用序号 "0"/"1" 作标识，
             v-model 值匹配不到任何页签 → 两个 pane 都不激活、内容区空白 -->
        <el-tab-pane :disabled="isOffline" :label="isNarrow ? t('topicDetail.live.tabNarrow') : t('topicDetail.live.tabFull')" name="live">
          <!--
            消息查询 = 实时主内容。
            时间定位合并为「按 Offset / 按时间」两种模式，共用 Partition 选择；
            时间模式下点定位结果的跳转会自动切回 Offset 模式并查询。
          -->
          <el-card shadow="never">
            <template #header>
              <div class="card-header">
                <span style="font-weight: 600">{{ t('topicDetail.live.queryTitle') }}</span>
                <div class="card-header-right">
                  <el-radio-group v-model="queryMode" size="small">
                    <el-radio-button value="offset">{{ t('topicDetail.live.byOffset') }}</el-radio-button>
                    <el-radio-button value="time">{{ t('topicDetail.live.byTime') }}</el-radio-button>
                  </el-radio-group>
                  <!-- 实时尾部跟随：轮询现有 /messages 接口，offset=已加载尾部+1 追加，零后端改动 -->
                  <el-switch
                    v-model="liveOn"
                    :disabled="queryMode !== 'offset'"
                    :active-text="t('topicDetail.live.live')"
                    :inactive-text="t('topicDetail.live.live')"
                    inline-prompt
                    size="small"
                  />
                  <el-select
                    v-if="liveOn"
                    v-model="liveInterval"
                    size="small"
                    style="width: 88px"
                  >
                    <el-option :label="t('topicDetail.live.seconds', {n: 2})" :value="2000" />
                    <el-option :label="t('topicDetail.live.seconds', {n: 5})" :value="5000" />
                    <el-option :label="t('topicDetail.live.seconds', {n: 10})" :value="10000" />
                    <el-option :label="t('topicDetail.live.seconds', {n: 30})" :value="30000" />
                  </el-select>
                </div>
              </div>
            </template>

            <!-- Offset 模式：按位点直接拉取 -->
            <el-form
              v-if="queryMode === 'offset'"
              :inline="true"
              :model="queryForm"
              style="margin-bottom: 16px"
            >
              <el-form-item label="Partition">
                <el-select v-model="queryForm.partition" style="width: 120px">
                  <el-option
                    v-for="p in partitions"
                    :key="p"
                    :label="'Partition ' + p"
                    :value="p"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="Offset">
                <el-input-number v-model="queryForm.offset" :min="0" style="width: 160px" />
              </el-form-item>
              <el-form-item label="Count">
                <el-input-number
                  v-model="queryForm.count"
                  :max="1000"
                  :min="1"
                  style="width: 160px"
                />
              </el-form-item>
              <el-form-item>
                <el-button :loading="loadingMessages" type="primary" @click="searchMessages()">
                  {{ t('common.search') }}
                </el-button>
              </el-form-item>
            </el-form>

            <!-- 时间模式：先定位各分区首条 ≥ 目标时间的 offset，点跳转回到 Offset 模式查询 -->
            <el-form v-else :inline="true" :model="lookupForm" style="margin-bottom: 16px">
              <el-form-item label="Partition">
                <el-select
                  v-model="lookupForm.partition"
                  clearable
                  :placeholder="t('topicDetail.live.allPartitions')"
                  style="width: 140px"
                >
                  <el-option
                    v-for="p in partitions"
                    :key="p"
                    :label="'Partition ' + p"
                    :value="p"
                  />
                </el-select>
              </el-form-item>
              <el-form-item :label="t('topicDetail.live.startTimeLabel')">
                <el-date-picker
                  v-model="lookupDate"
                  :placeholder="t('topicDetail.live.datePlaceholder')"
                  style="width: 200px"
                  type="datetime"
                />
              </el-form-item>
              <el-form-item>
                <el-button
                  :disabled="!lookupDate"
                  :loading="loadingLookup"
                  type="primary"
                  @click="searchByTimestamp"
                >
                  {{ t('topicDetail.live.locate') }}
                </el-button>
              </el-form-item>
            </el-form>

            <OffsetLookupResult
              v-if="queryMode === 'time' && lookupResult"
              :result="lookupResult"
              @jump="jumpToOffset"
            />

            <MessageTable
              :loading="loadingMessages"
              :messages="messages"
              @view-detail="showDetail($event)"
            />

            <div v-if="queryResult" class="query-summary">
              {{ t('topicDetail.live.returned', {n: queryResult.totalReturned}) }}
              <template v-if="queryResult.hasMore">
                · <el-button :disabled="reachedLimit || liveBusy" link type="primary" @click="loadMore">
                  {{ reachedLimit ? t('topicDetail.live.reachedLimit') : t('topicDetail.live.loadMore') }}
                </el-button>
              </template>
              <template v-else> · {{ t('topicDetail.live.endOfStream') }}</template>
            </div>
          </el-card>
        </el-tab-pane>

        <!-- 历史归档:离线可用;支持已删除 topic -->
        <el-tab-pane :label="isNarrow ? t('topicDetail.archive.tabNarrow') : t('topicDetail.archive.tabFull')" name="archive">
          <el-card shadow="never">
            <template #header>
              <div class="card-header">
                <span style="font-weight: 600">{{ t('topicDetail.archive.queryTitle') }}</span>
                <el-button
                  v-if="archiveTopicName"
                  plain
                  size="small"
                  type="danger"
                  @click="handleCleanArchive"
                >
                  {{ t('topicDetail.archive.cleanBtn') }}
                </el-button>
              </div>
            </template>

            <el-form :inline="true" :model="archiveForm" style="margin-bottom: 16px" @submit.prevent>
              <el-form-item label="Topic">
                <el-select
                  v-model="archiveTopicName"
                  clearable
                  filterable
                  :placeholder="t('topicDetail.archive.selectPlaceholder')"
                  style="width: 240px"
                >
                  <el-option
                    v-for="at in archiveTopics"
                    :key="at.topicName"
                    :label="at.topicName"
                    :value="at.topicName"
                  >
                    <span class="archive-option">
                      <span>{{ at.topicName }}</span>
                      <el-tag v-if="at.deleted === 1" size="small" type="info">{{ t('topicDetail.archive.deletedTag') }}</el-tag>
                      <span class="archive-count">{{ t('topicDetail.archive.countSuffix', {n: at.messageCount.toLocaleString()}) }}</span>
                    </span>
                  </el-option>
                </el-select>
              </el-form-item>
              <el-form-item label="Partition">
                <el-checkbox v-model="archiveUsePartition" style="margin-right: 8px">{{ t('topicDetail.archive.specify') }}</el-checkbox>
                <el-input-number
                  v-if="archiveUsePartition"
                  v-model="archiveForm.partition"
                  :min="0"
                  :precision="0"
                  style="width: 120px"
                />
              </el-form-item>
              <el-form-item :label="t('topicDetail.archive.timeRange')">
                <el-date-picker
                  v-model="archiveTimeRange"
                  :end-placeholder="t('topicDetail.archive.rangeEnd')"
                  :start-placeholder="t('topicDetail.archive.rangeStart')"
                  style="width: 320px"
                  type="datetimerange"
                />
              </el-form-item>
              <el-form-item :label="t('topicDetail.archive.limitLabel')">
                <el-input-number v-model="archiveForm.limit" :max="1000" :min="1" style="width: 120px" />
              </el-form-item>
              <el-form-item>
                <el-button :disabled="!archiveTopicName" :loading="loadingArchive" type="primary" @click="queryArchive()">
                  {{ t('common.search') }}
                </el-button>
              </el-form-item>
            </el-form>

            <MessageTable
              :loading="loadingArchive"
              :messages="archiveMessages"
              @view-detail="showDetail($event)"
            />

            <el-empty
              v-if="!loadingArchive && archiveTopics.length === 0"
              :description="t('topicDetail.archive.empty')"
            />

            <div v-if="archiveResult" class="query-summary">
              {{ t('topicDetail.live.returned', {n: archiveResult.totalReturned}) }}
              <template v-if="archiveResult.hasMore">
                · <el-button :disabled="archiveReachedLimit" link type="primary" @click="loadMoreArchive">
                  {{ archiveReachedLimit ? t('topicDetail.live.reachedLimit') : t('topicDetail.live.loadMore') }}
                </el-button>
              </template>
              <template v-else> · {{ t('topicDetail.live.endOfStream') }}</template>
            </div>
          </el-card>
        </el-tab-pane>

        <el-tab-pane name="meta">
          <!-- 页签名内嵌分区数，不切页签也能看到规模 -->
          <template #label>
            <span>{{ isNarrow ? t('topicDetail.meta.tabNarrow') : t('topicDetail.meta.tabFull') }}</span>
            <el-tag size="small" style="margin-left: 6px" type="info">
              {{ partitions.length }}
            </el-tag>
          </template>
          <div class="pane-toolbar">
            <el-button :loading="loadingTopic" size="small" @click="loadTopic">{{ t('common.refresh') }}</el-button>
          </div>
          <el-table
            v-loading="loadingTopic"
            :data="topicDetail?.partitions || []"
            size="small"
            stripe
          >
            <el-table-column label="Partition" prop="partition" width="90" />
            <el-table-column label="Leader" width="80">
              <!-- 后端在分区离线 / leader 迁移中时返回 -1，显示为「离线」而不是裸数字 -->
              <template #default="{ row }">
                <span v-if="row.leader < 0" style="color: #e6a23c">{{ t('common.offline') }}</span>
                <span v-else>{{ row.leader }}</span>
              </template>
            </el-table-column>
            <!-- 窄档隐藏副本细节列:离线排障时才需要,窄窗口下让位给 offset 列 -->
            <el-table-column v-if="!isNarrow" label="Replicas" min-width="140">
              <template #default="{ row }">{{ row.replicas?.join(', ') }}</template>
            </el-table-column>
            <el-table-column v-if="!isNarrow" label="ISR" min-width="140">
              <template #default="{ row }">{{ row.isr?.join(', ') }}</template>
            </el-table-column>
            <el-table-column label="Begin Offset" min-width="110" prop="beginningOffset" />
            <el-table-column label="End Offset" min-width="110" prop="endOffset" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>

    <MessageDetail v-model:visible="drawerVisible" :record="selectedMessage" />

    <!-- 弹窗 -->
    <EditTopicConfigsDialog
      v-model:visible="showEditConfigs"
      :cluster-id="activeClusterId ?? 0"
      :topic="topicName"
      @updated="loadTopic"
    />
    <ExpandPartitionsDialog
      v-model:visible="showExpand"
      :cluster-id="activeClusterId ?? 0"
      :topic="topicName"
      :current-partitions="partitions.length"
      @updated="loadTopic"
    />
    <ProduceMessageDialog
      v-model:visible="showProduce"
      :cluster-id="activeClusterId ?? 0"
      :topic="topicName"
      @produced="onProduced"
    />
  </div>
</template>

<script setup lang="ts">
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {useI18n} from 'vue-i18n'
import {
  ApiError,
  type ArchiveMessageQueryParams,
  type ArchiveMessageQueryResult,
  type ArchiveTopicSummary,
  deleteArchive,
  getArchiveTopics,
  getTopicDetail,
  type MessageQueryResult,
  type MessageRecord,
  offsetsForTimes,
  queryArchiveMessages,
  queryMessages,
  type TopicDetail,
  type TopicOffsetLookup,
} from '../api'
import MessageTable from '../components/MessageTable.vue'
import MessageDetail from '../components/MessageDetail.vue'
import OffsetLookupResult from '../components/OffsetLookupResult.vue'
import EditTopicConfigsDialog from '../components/EditTopicConfigsDialog.vue'
import ExpandPartitionsDialog from '../components/ExpandPartitionsDialog.vue'
import ProduceMessageDialog from '../components/ProduceMessageDialog.vue'
import {useOffsetRecovery} from '../composables/useOffsetRecovery'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import {useResponsive} from '../composables/useResponsive'
import {injectGlobalState} from '../composables/useGlobalState'
import {ElMessage} from 'element-plus'

const route = useRoute()
const topicName = computed(() => route.params.name as string)
const {t} = useI18n()

const {confirm} = useCrudConfirm()
const {isNarrow} = useResponsive()
const {activeClusterId, activeCluster} = injectGlobalState()

/** 激活集群离线(全局轮询驱动):实时 tab 禁用、自动切归档、Kafka 写操作禁用 */
const isOffline = computed(() => activeCluster.value?.displayState === 'OFFLINE')

const {offerRecovery} = useOffsetRecovery()

const topicDetail = ref<TopicDetail | null>(null)
const loadingTopic = ref(false)
const loadingMessages = ref(false)
const loadingLookup = ref(false)
const loadFailed = ref(false)
const loadError = ref('')
/** 加载失败的 HTTP 业务错误码，用于按码分支（40401 = Topic 不存在） */
const loadErrorCode = ref<number | undefined>(undefined)

/** 40401：Topic 不存在，重试没有意义 */
const isTopicMissing = computed(() => loadErrorCode.value === 40401)

const loadFailDescription = computed(() =>
  isTopicMissing.value
    ? t('topicDetail.page.missingTopic', {name: topicName.value, msg: loadError.value})
    : t('topicDetail.page.loadFailedTopic', {name: topicName.value, msg: loadError.value}),
)

const messages = ref<MessageRecord[]>([])
const queryResult = ref<MessageQueryResult | null>(null)
const lookupResult = ref<TopicOffsetLookup | null>(null)
const lookupDate = ref<Date | null>(null)

const drawerVisible = ref(false)
const selectedMessage = ref<MessageRecord | null>(null)

const showEditConfigs = ref(false)
const showExpand = ref(false)
const showProduce = ref(false)

/** 查询模式：offset = 按位点拉取；time = 按时间定位后跳转（合并原「按时间戳定位」卡片） */
const queryMode = ref<'offset' | 'time'>('offset')
/**
 * 页签：live = 实时消息查询；archive = 历史归档；meta = Partition 元数据。
 * 离线集群 live 禁用、自动落在 archive。
 */
const activeTab = ref<'live' | 'archive' | 'meta'>('live')

const queryForm = ref({
  partition: 0,
  offset: 0,
  count: 100,
})

/**
 * "加载更多"累计条数上限:每批最多 count 条,连续加载会让 messages 只增不减,
 * 浏览器内存与 el-table 渲染行数线性增长;单条消息很大时可下调到 2000。
 */
const MAX_LOADED_MESSAGES = 5000

/** 达到上限时禁用"加载更多":hasMore 仍可能为 true(上限是前端保护,不是数据边界)。 */
const reachedLimit = computed(() => messages.value.length >= MAX_LOADED_MESSAGES)

const lookupForm = ref<{partition: number | undefined}>({
  partition: undefined,
})

const partitions = computed(() => {
  return topicDetail.value?.partitions?.map((p) => p.partition) || []
})

// ============================================================
// 历史归档
// ============================================================

const archiveTopics = ref<ArchiveTopicSummary[]>([])
const loadingArchiveTopics = ref(false)
const archiveMessages = ref<MessageRecord[]>([])
const archiveResult = ref<ArchiveMessageQueryResult | null>(null)
const loadingArchive = ref(false)

/** 下拉值:选项自带"已删除"tag 与归档条数,选中值仅用于查询参数 */
const archiveTopicName = ref('')
const archiveForm = ref({
  partition: 0,
  limit: 100,
})
const archiveUsePartition = ref(false)
const archiveTimeRange = ref<[Date, Date] | null>(null)

const MAX_LOADED_ARCHIVE = 5000
const archiveReachedLimit = computed(() => archiveMessages.value.length >= MAX_LOADED_ARCHIVE)

const loadArchiveTopics = async () => {
  const cid = activeClusterId.value
  if (cid == null) return
  loadingArchiveTopics.value = true
  try {
    const res = await getArchiveTopics(cid)
    archiveTopics.value = res.data || []
  } catch (e: any) {
    // 归档台账拉不到(如外部数据源不可达)不影响实时 tab,只让归档 tab 空转
    archiveTopics.value = []
    ElMessage.warning(t('topicDetail.archive.loadTopicsFailed', {msg: e.message}))
  } finally {
    loadingArchiveTopics.value = false
  }
}

/** 组装归档查询参数:过滤条件(分区/时间/条数)与翻页游标分离,加载更多时复用过滤条件 */
const buildArchiveParams = (): ArchiveMessageQueryParams => {
  const cid = activeClusterId.value
  const params: ArchiveMessageQueryParams = {
    topic: archiveTopicName.value,
    limit: archiveForm.value.limit,
  }
  if (archiveUsePartition.value) {
    params.partition = archiveForm.value.partition
  }
  if (archiveTimeRange.value) {
    params.fromTime = archiveTimeRange.value[0].getTime()
    params.toTime = archiveTimeRange.value[1].getTime()
  }
  return params
}

const queryArchive = async () => {
  const cid = activeClusterId.value
  if (cid == null || !archiveTopicName.value) return
  loadingArchive.value = true
  archiveMessages.value = []
  try {
    const res = await queryArchiveMessages(cid, buildArchiveParams())
    archiveResult.value = res.data
    archiveMessages.value = res.data.records
  } catch (e: any) {
    // 40001 = 参数校验失败(topic 空 / limit 越界等,正常已被表单拦住)
    ElMessage.error(e.message)
  } finally {
    loadingArchive.value = false
  }
}

const loadMoreArchive = async () => {
  const cid = activeClusterId.value
  if (cid == null || !archiveResult.value || !archiveMessages.value.length) return
  if (archiveReachedLimit.value) {
    ElMessage.warning(t('topicDetail.archive.loadLimitReached', {n: MAX_LOADED_ARCHIVE}))
    return
  }
  const params = buildArchiveParams()
  params.offsetFrom = archiveResult.value.nextOffset
  /**
   * 复合游标:未指定分区时,翻页必须把 nextPartition + nextOffset
   * 成对回传(offsetFromPartition + offsetFrom),否则多分区 topic 会跳行;
   * 指定分区时只传 offsetFrom 即可(游标恒在该分区内)。
   */
  if (!archiveUsePartition.value && archiveResult.value.nextPartition != null) {
    params.offsetFromPartition = archiveResult.value.nextPartition
  }
  loadingArchive.value = true
  try {
    const res = await queryArchiveMessages(cid, params)
    archiveResult.value = res.data
    archiveMessages.value = [...archiveMessages.value, ...res.data.records]
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    loadingArchive.value = false
  }
}

const handleCleanArchive = async () => {
  const cid = activeClusterId.value
  const topic = archiveTopicName.value
  if (cid == null || !topic) return
  const ok = await confirm(
    t('topicDetail.archive.confirmCleanMsg', {topic}),
    t('topicDetail.archive.confirmCleanTitle'),
    'warning',
  )
  if (!ok) return
  try {
    const res = await deleteArchive(cid, topic)
    ElMessage.success(t('topicDetail.archive.cleaned', {n: res.data}))
    archiveMessages.value = []
    archiveResult.value = null
    await loadArchiveTopics()
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

// ============================================================
// 实时消息(原有逻辑,全部加 clusterId)
// ============================================================

const loadTopic = async () => {
  const cid = activeClusterId.value
  if (cid == null) return
  loadingTopic.value = true
  loadFailed.value = false
  loadError.value = ''
  loadErrorCode.value = undefined
  try {
    const res = await getTopicDetail(cid, topicName.value)
    topicDetail.value = res.data
  } catch (e: any) {
    // 50302 = 集群离线/连接中:页面交给归档 tab + 离线横幅,不算加载失败
    if ((e as ApiError)?.code === 50302) {
      topicDetail.value = null
      activeTab.value = 'archive'
      return
    }
    loadFailed.value = true
    loadError.value = e.message
    loadErrorCode.value = e instanceof ApiError ? e.code : undefined
    ElMessage.error(e.message)
  } finally {
    loadingTopic.value = false
  }
}

/**
 * 查询失败的统一处理。
 * 40001（offset 已被 retention 清理 / compact 跳过）时引导从服务端给出的
 * earliest offset 重查；其余错误交回调用方按原逻辑弹出 msg。
 * @returns true = 已被恢复路径接管，调用方不要再弹错误
 */
const handleQueryError = async (e: any, allowRecovery = true): Promise<boolean> => {
  const earliest = allowRecovery ? await offerRecovery(e) : null
  if (earliest === null) return false
  ElMessage.warning(t('topicDetail.recovered', {n: earliest}))
  queryForm.value.offset = earliest
  // allowRecovery=false：避免恢复后的查询再次越界时无限弹窗
  await searchMessages(false)
  return true
}

const searchMessages = async (allowRecovery = true) => {
  const cid = activeClusterId.value
  if (cid == null) return
  querySeq.value++
  loadingMessages.value = true
  messages.value = []
  try {
    const res = await queryMessages(cid, {
      topic: topicName.value,
      partition: queryForm.value.partition,
      offset: queryForm.value.offset,
      count: queryForm.value.count,
    })
    queryResult.value = res.data
    messages.value = res.data.records
  } catch (e: any) {
    if (!(await handleQueryError(e, allowRecovery))) {
      ElMessage.error(e.message)
    }
  } finally {
    loadingMessages.value = false
  }
}

const loadMore = async () => {
  const cid = activeClusterId.value
  if (cid == null) return
  querySeq.value++
  if (!messages.value.length) return
  if (reachedLimit.value) {
    // 不能静默失败:否则表现为"按钮点了没反应"
    ElMessage.warning(t('topicDetail.limitReachedLive', {n: MAX_LOADED_MESSAGES}))
    return
  }
  const lastMsg = messages.value[messages.value.length - 1]
  const nextOffset = lastMsg.offset + 1
  loadingMessages.value = true
  try {
    const res = await queryMessages(cid, {
      topic: topicName.value,
      partition: queryForm.value.partition,
      offset: nextOffset,
      count: queryForm.value.count,
    })
    queryResult.value = res.data
    messages.value = [...messages.value, ...res.data.records]
  } catch (e: any) {
    // 两批之间 retention 前进，nextOffset 也可能落到 earliest 之前 → 同样走恢复路径
    if (!(await handleQueryError(e))) {
      ElMessage.error(e.message)
    }
  } finally {
    loadingMessages.value = false
  }
}

/** ============ 实时尾部跟随（前端轮询现有接口，零后端改动） ============ */
const liveOn = ref(false)
const liveInterval = ref(5000)
const liveBusy = ref(false)
/**
 * 基线序号：手动查询 / 加载更多会重置消息列表基线；
 * 轮询响应返回时若序号已变，说明基线被重置，丢弃该批避免旧数据混入。
 */
const querySeq = ref(0)
let liveTimer: number | null = null

const stopLive = (reason?: string) => {
  liveOn.value = false
  if (liveTimer !== null) {
    clearTimeout(liveTimer)
    liveTimer = null
  }
  if (reason) ElMessage.info(reason)
}

const scheduleLive = () => {
  if (!liveOn.value) return
  if (liveTimer !== null) clearTimeout(liveTimer)
  // 递归 setTimeout 而非 setInterval：一轮请求耗时超过间隔时不会重叠
  liveTimer = window.setTimeout(() => pollLiveOnce(), liveInterval.value)
}

const isNearBottom = () =>
  window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 80

const scrollToBottom = () => {
  nextTick(() => window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'smooth' }))
}

const pollLiveOnce = async () => {
  const cid = activeClusterId.value
  if (cid == null || !liveOn.value || liveBusy.value) return
  if (!queryResult.value) {
    stopLive()
    return
  }
  liveBusy.value = true
  const seq = querySeq.value
  try {
    // 追平循环：一轮内把 hasMore 拉完，防止消息到达速率超过 count 时跳漏
    while (liveOn.value && !reachedLimit.value) {
      const lastMsg = messages.value[messages.value.length - 1]
      // 用上次响应的分区（防表单改了分区但还没重查的漂移）；无消息时从查询起点起步
      const cursor = lastMsg ? lastMsg.offset + 1 : queryForm.value.offset
      const res = await queryMessages(cid, {
        topic: topicName.value,
        partition: queryResult.value.partition,
        offset: cursor,
        count: queryForm.value.count,
      })
      if (seq !== querySeq.value) break
      queryResult.value = res.data
      if (!res.data.records.length) break
      const wasNearBottom = isNearBottom()
      messages.value = [...messages.value, ...res.data.records]
      if (wasNearBottom) scrollToBottom()
      if (!res.data.hasMore) break
    }
    if (reachedLimit.value) {
      stopLive(t('topicDetail.liveStop.loadLimit', {n: MAX_LOADED_MESSAGES}))
      return
    }
  } catch (e: any) {
    // 40001 在轮询里不弹恢复框（打断阅读）：停掉并提示手动重查
    stopLive(
      e instanceof ApiError && e.code === 40001
        ? t('topicDetail.liveStop.cleaned')
        : t('topicDetail.liveStop.generic', {msg: e.message}),
    )
    return
  } finally {
    liveBusy.value = false
  }
  scheduleLive()
}

watch(liveOn, async (on) => {
  if (!on) {
    if (liveTimer !== null) {
      clearTimeout(liveTimer)
      liveTimer = null
    }
    return
  }
  // 没有基线（进页面还没查过）就先查一次，从当前表单 offset 起步
  if (!queryResult.value) {
    await searchMessages()
    if (!liveOn.value || !queryResult.value) return
  }
  scheduleLive()
})

// 尾部跟随只适用于 Offset 模式
watch(queryMode, (mode) => {
  if (mode !== 'offset' && liveOn.value) {
    stopLive(t('topicDetail.liveStop.modeSwitch'))
  }
})

watch(liveInterval, () => {
  if (liveOn.value) scheduleLive()
})

// 浏览器页签隐藏时暂停轮询，回来继续，省资源也防后台堆积
const onVisibilityChange = () => {
  if (document.hidden) {
    if (liveTimer !== null) {
      clearTimeout(liveTimer)
      liveTimer = null
    }
  } else if (liveOn.value) {
    scheduleLive()
  }
}

onMounted(() => document.addEventListener('visibilitychange', onVisibilityChange))
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange)
  stopLive()
})

/** 按时间戳定位各分区首条消息 offset */
const searchByTimestamp = async () => {
  const cid = activeClusterId.value
  if (cid == null) return
  if (!lookupDate.value) {
    ElMessage.warning(t('topicDetail.live.selectStartTime'))
    return
  }

  loadingLookup.value = true
  lookupResult.value = null
  try {
    const params: {topic: string; timestamp: number; partition?: number} = {
      topic: topicName.value,
      timestamp: lookupDate.value.getTime(),
    }
    // partition 缺省 = 查询全部分区
    if (lookupForm.value.partition !== undefined && lookupForm.value.partition !== null) {
      params.partition = lookupForm.value.partition
    }

    const res = await offsetsForTimes(cid, params)
    lookupResult.value = res.data
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    loadingLookup.value = false
  }
}

/** 从定位结果跳到消息查询：切回 Offset 模式，写入分区与 offset 后直接拉取 */
const jumpToOffset = (partition: number, offset: number) => {
  queryMode.value = 'offset'
  queryForm.value.partition = partition
  queryForm.value.offset = offset
  searchMessages()
}

/** 发送成功后定位到刚写入的 offset，打通「生产 → 查询」闭环 */
const onProduced = (result: {partition: number; offset: number}) => {
  queryMode.value = 'offset'
  queryForm.value.partition = result.partition
  queryForm.value.offset = result.offset
  queryForm.value.count = 10
  searchMessages()
}

const showDetail = (msg: MessageRecord) => {
  selectedMessage.value = msg
  drawerVisible.value = true
}

// ---- 启动 / 重置 ----

const resetAndLoad = () => {
  stopLive()
  topicDetail.value = null
  messages.value = []
  queryResult.value = null
  lookupResult.value = null
  lookupDate.value = null
  queryForm.value = {partition: 0, offset: 0, count: 100}
  // 归档区同步重置:换集群/换 topic 后旧归档结果不能残留
  archiveMessages.value = []
  archiveResult.value = null
  archiveTopicName.value = ''
  archiveUsePartition.value = false
  archiveTimeRange.value = null
  loadTopic()
  loadArchiveTopics()
}

onMounted(() => {
  // 深链:TopicList 的已删除 topic 带 ?tab=archive 直落归档 tab
  if (route.query.tab === 'archive') {
    activeTab.value = 'archive'
  }
  // 离线集群落在归档 tab
  if (isOffline.value) {
    activeTab.value = 'archive'
  }
  resetAndLoad()
})

/**
 * 同组件复用：vue-router 对 /topics/:name 的 A→B 导航默认复用实例、不重新 mount。
 * 必须监听 topicName / activeClusterId 变化，重置本页状态并重新拉取，
 * 否则分区表/查询结果会串 topic 或串集群。
 */
watch([topicName, activeClusterId], () => {
  // 深链参数只在该 topic 首次进入时生效,topic 内部切换不再读 query
  if (route.query.tab === 'archive') {
    activeTab.value = 'archive'
  }
  resetAndLoad()
})

// 离线状态变化:离线时自动切归档并停掉实时轮询;恢复在线时回到实时 tab
watch(isOffline, (off) => {
  if (off) {
    if (activeTab.value === 'live') activeTab.value = 'archive'
    stopLive(t('topicDetail.liveStop.offline'))
  } else if (!loadFailed.value) {
    // 恢复在线:静默补拉分区元数据(失败由 loadTopic 自己提示)
    loadTopic()
  }
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.card-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.pane-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}
.query-summary {
  margin-top: 12px;
  color: #909399;
  font-size: 13px;
}
.archive-option {
  display: flex;
  align-items: center;
  gap: 8px;
}
.archive-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
