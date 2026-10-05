<template>
  <el-dialog
    :model-value="visible"
    :title="t('topicDialogs.editConfigs.title', {topic})"
    width="min(720px, 92%)"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="t('topicDialogs.editConfigs.incrementalNotice')"
      style="margin-bottom: 16px"
    />

    <el-table :data="rows" v-loading="loading" size="small" stripe max-height="360">
      <el-table-column :label="t('topicDialogs.editConfigs.colKey')" min-width="180" show-overflow-tooltip>
        <!-- 新增行必须可输入键名；已有行只读（键不可改，只能改值/恢复默认） -->
        <template #default="{ row }">
          <el-input v-if="row.isNew" v-model="row.key" :placeholder="t('topicDialogs.editConfigs.newKeyPlaceholder')" clearable size="small" />
          <span v-else>{{ row.key }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('topicDialogs.editConfigs.colCurrentValue')" min-width="160">
        <template #default="{ row }">
          <span v-if="row.restore" style="color: var(--el-text-color-secondary)">
            {{ t('topicDialogs.editConfigs.restoreSuffix', {value: row.originalValue}) }}
          </span>
          <span v-else>{{ row.originalValue }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('topicDialogs.editConfigs.colNewValue')" min-width="160">
        <template #default="{ row }">
          <el-input
            v-model="row.value"
            size="small"
            :disabled="row.restore"
            :placeholder="t('topicDialogs.editConfigs.newValuePlaceholder')"
          />
        </template>
      </el-table-column>
      <el-table-column :label="t('topicDialogs.editConfigs.colRestore')" align="center" width="110">
        <template #default="{ row }">
          <el-checkbox v-model="row.restore" />
        </template>
      </el-table-column>
      <el-table-column :label="t('common.operation')" align="center" width="80">
        <template #default="{ row, $index }">
          <el-button
            v-if="row.isNew"
            type="danger"
            link
            size="small"
            @click="removeRow($index)"
          >
            {{ t('topicDialogs.editConfigs.remove') }}
          </el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 12px">
      <el-button size="small" @click="addRow">{{ t('topicDialogs.editConfigs.addRow') }}</el-button>
    </div>

    <!-- 40004 等后端校验错误就地展示，不关闭弹窗 -->
    <el-alert
      v-if="errorMsg"
      type="error"
      :closable="false"
      show-icon
      :title="errorMsg"
      style="margin-top: 12px"
    />

    <template #footer>
      <el-button @click="close">{{ t('common.cancel') }}</el-button>
      <el-button :loading="submitting" type="primary" @click="submit">{{ t('topicDialogs.editConfigs.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {getTopicConfigs, updateTopicConfigs} from '../api'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import {ElMessage} from 'element-plus'

interface ConfigRow {
  key: string
  /** 从后端读到的原始值（展示用，不提交） */
  originalValue: string
  /** 待提交的新值；空串或 restore=true 时提交 null */
  value: string
  restore: boolean
  /** 新增行（后端原本没有该键） */
  isNew: boolean
}

const props = defineProps<{
  visible: boolean
  /** 目标集群 id（多集群改造） */
  clusterId: number
  topic: string
}>()

const emit = defineEmits<{
  'update:visible': [v: boolean]
  updated: []
}>()

const {confirm} = useCrudConfirm()
const {t} = useI18n()

const rows = ref<ConfigRow[]>([])
const loading = ref(false)
const submitting = ref(false)
const errorMsg = ref('')

/** 打开时拉取当前配置 */
watch(
  () => props.visible,
  async (v) => {
    if (!v) return
    errorMsg.value = ''
    rows.value = []
    if (!props.topic) return

    loading.value = true
    try {
      const res = await getTopicConfigs(props.clusterId, props.topic)
      rows.value = Object.entries(res.data.configs || {}).map(([key, value]) => ({
        key,
        originalValue: value,
        value,
        restore: false,
        isNew: false,
      }))
    } catch (e: any) {
      errorMsg.value = e.message
    } finally {
      loading.value = false
    }
  },
  {immediate: true},
)

const addRow = () => {
  rows.value.push({key: '', originalValue: '', value: '', restore: false, isNew: true})
}

const removeRow = (idx: number) => {
  rows.value.splice(idx, 1)
}

const close = () => {
  emit('update:visible', false)
}

const submit = async () => {
  errorMsg.value = ''

  /**
   * 客户端 diff：只提交真正变更的行 —— 新增 / 勢复默认 / 值被修改。
   * 根因：后端 GET 不做 isDefault 过滤，返回 describeConfigs 全部条目（典型 100+ 键）；
   * 若全量 SET，一次保存会把 broker 默认值全部写成 topic 级显式覆盖，且任一键被拒即整批 40004。
   */
  const configs: Record<string, string | null> = {}
  // 已有行的键是只读的，新增键不得与之重名
  const existingKeys = new Set(
    rows.value.filter((r) => !r.isNew).map((r) => r.key),
  )
  const seen = new Set<string>()
  for (const row of rows.value) {
    if (!row.isNew && !row.restore && row.value === row.originalValue) continue
    const key = row.key?.trim()
    if (!key) {
      errorMsg.value = row.isNew ? t('topicDialogs.editConfigs.errEmptyKey') : ''
      return
    }
    if (seen.has(key) || existingKeys.has(key)) {
      errorMsg.value = t('topicDialogs.editConfigs.errDuplicateKey', {key})
      return
    }
    seen.add(key)
    if (row.restore || row.value === '') {
      configs[key] = null
    } else {
      configs[key] = row.value
    }
  }

  if (Object.keys(configs).length === 0) {
    errorMsg.value = t('topicDialogs.editConfigs.errNothingToSubmit')
    return
  }

  const ok = await confirm(
    t('topicDialogs.editConfigs.confirmMsg', {topic: props.topic, count: Object.keys(configs).length}),
    t('topicDialogs.editConfigs.confirmTitle'),
    'warning',
  )
  if (!ok) return

  submitting.value = true
  try {
    await updateTopicConfigs(props.clusterId, props.topic, {configs})
    ElMessage.success(t('topicDialogs.editConfigs.updated'))
    emit('updated')
    close()
  } catch (e: any) {
    // 40004 配置键值非法 / 40401 Topic 不存在：保留弹窗，就地展示
    errorMsg.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>
