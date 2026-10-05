<template>
  <el-dialog
    :model-value="visible"
    :title="t('topicDialogs.resetOffsets.title', {group})"
    width="min(720px, 92%)"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      :title="t('topicDialogs.resetOffsets.notice')"
      style="margin-bottom: 16px"
    />

    <el-form :model="form" :rules="rules" ref="formRef" label-width="120px">
      <el-form-item label="Topic" prop="topic">
        <el-select
          v-model="form.topic"
          filterable
          allow-create
          default-first-option
          :placeholder="t('topicDialogs.resetOffsets.topicPlaceholder')"
          style="width: 100%"
        >
          <el-option v-for="t in topics" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>

      <el-form-item :label="t('topicDialogs.resetOffsets.partitionsLabel')">
        <el-select
          v-model="form.partitions"
          multiple
          filterable
          allow-create
          default-first-option
          :placeholder="t('topicDialogs.resetOffsets.partitionsPlaceholder')"
          style="width: 100%"
        >
          <el-option
            v-for="p in availablePartitions"
            :key="p"
            :label="`Partition ${p}`"
            :value="p"
          />
        </el-select>
      </el-form-item>

      <el-form-item :label="t('topicDialogs.resetOffsets.strategyLabel')" prop="strategy">
        <el-radio-group v-model="form.strategy">
          <el-radio value="EARLIEST">{{ t('topicDialogs.resetOffsets.strategyEarliest') }}</el-radio>
          <el-radio value="LATEST">{{ t('topicDialogs.resetOffsets.strategyLatest') }}</el-radio>
          <el-radio value="TO_OFFSET">{{ t('topicDialogs.resetOffsets.strategyToOffset') }}</el-radio>
          <el-radio value="TO_TIMESTAMP">{{ t('topicDialogs.resetOffsets.strategyToTimestamp') }}</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="form.strategy === 'TO_OFFSET'" :label="t('topicDialogs.resetOffsets.targetOffsetLabel')" prop="offset">
        <el-input-number v-model="form.offset" :min="0" :precision="0" style="width: 200px" />
      </el-form-item>

      <el-form-item v-if="form.strategy === 'TO_TIMESTAMP'" :label="t('topicDialogs.resetOffsets.targetTimestampLabel')" prop="timestamp">
        <el-date-picker
          v-model="dateValue"
          type="datetime"
          :placeholder="t('topicDialogs.resetOffsets.datePlaceholder')"
          style="width: 200px"
          @change="onDateChange"
        />
        <span class="form-tip">{{ form.timestamp || '-' }} ms</span>
      </el-form-item>
    </el-form>

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
      <el-button :loading="submitting" type="primary" @click="submit">{{ t('topicDialogs.resetOffsets.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {computed, reactive, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {getTopics, resetOffsets} from '../api'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage} from 'element-plus'

const {t} = useI18n()

const props = defineProps<{
  visible: boolean
  /** 目标集群 id（多集群改造） */
  clusterId: number
  group: string
  /** 可选：父组件传入的候选分区号列表（来自组概览），用于下拉提示 */
  availablePartitions?: number[]
}>()

const emit = defineEmits<{
  'update:visible': [v: boolean]
  updated: []
}>()

const {confirm} = useCrudConfirm()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMsg = ref('')
const dateValue = ref<Date | null>(null)
/** Topic 下拉候选(计划 §4.2.4「topic 下拉」)；allow-create 允许手输未列出 topic */
const topics = ref<string[]>([])

const form = reactive<{
  topic: string
  partitions: number[]
  strategy: 'EARLIEST' | 'LATEST' | 'TO_OFFSET' | 'TO_TIMESTAMP'
  offset: number | undefined
  timestamp: number | undefined
}>({
  topic: '',
  partitions: [],
  strategy: 'EARLIEST',
  offset: undefined,
  timestamp: undefined,
})

// 条件必填：TO_OFFSET 必须带 offset，TO_TIMESTAMP 必须带 timestamp
const rules = computed<FormRules>(() => ({
  topic: [{required: true, message: t('topicDialogs.resetOffsets.ruleTopicRequired'), trigger: 'blur'}],
  strategy: [{required: true, message: t('topicDialogs.resetOffsets.ruleStrategyRequired'), trigger: 'change'}],
  offset: [
    {
      validator: (_rule, value, callback) => {
        if (form.strategy === 'TO_OFFSET' && (value === undefined || value === null)) {
          callback(new Error(t('topicDialogs.resetOffsets.ruleOffsetRequired')))
        } else {
          callback()
        }
      },
      trigger: 'change',
    },
  ],
  timestamp: [
    {
      validator: (_rule, value, callback) => {
        if (form.strategy === 'TO_TIMESTAMP' && (value === undefined || value === null)) {
          callback(new Error(t('topicDialogs.resetOffsets.ruleTimestampRequired')))
        } else {
          callback()
        }
      },
      trigger: 'change',
    },
  ],
}))

const onDateChange = (v: Date | null) => {
  form.timestamp = v ? v.getTime() : undefined
}

/**
 * 把下拉多选（allow-create 会混入字符串）规范化为非负整数列表，并逐项报错。
 * @returns ok=false 时 error 即用户可见的阻断原因
 */
const normalizePartitions = (raw: (string | number)[]): {
  ok: boolean
  partitions: number[]
  error: string
} => {
  const partitions: number[] = []
  const seen = new Set<number>()
  const invalid: string[] = []
  for (const item of raw) {
    // 字符串形态先校验，避免 Number(' ')/Number('') === 0 的静默放行
    if (typeof item === 'string') {
      const trimmed = item.trim()
      if (trimmed === '' || Number.isNaN(Number(trimmed))) {
        invalid.push(item)
        continue
      }
      if (!/^\d+$/.test(trimmed)) {
        invalid.push(item)
        continue
      }
      const n = Number(trimmed)
      if (n < 0) {
        invalid.push(item)
        continue
      }
      if (!seen.has(n)) {
        seen.add(n)
        partitions.push(n)
      }
      continue
    }
    if (typeof item !== 'number' || !Number.isInteger(item) || item < 0) {
      invalid.push(String(item))
      continue
    }
    if (!seen.has(item)) {
      seen.add(item)
      partitions.push(item)
    }
  }
  if (invalid.length > 0) {
    return {
      ok: false,
      partitions: [],
      error: t('topicDialogs.resetOffsets.errInvalidPartitions', {list: invalid.join(', ')}),
    }
  }
  return {ok: true, partitions, error: ''}
}

/** 打开时拉取 topic 候选，用于下拉提示 */
const loadTopics = async () => {
  try {
    const res = await getTopics(props.clusterId)
    topics.value = res.data
  } catch {
    // 候选列表是辅助数据，失败静默，仍可用 allow-create 手输
    topics.value = []
  }
}

watch(
  () => props.visible,
  (v) => {
    if (!v) return
    errorMsg.value = ''
    form.topic = ''
    form.partitions = []
    form.strategy = 'EARLIEST'
    form.offset = undefined
    form.timestamp = undefined
    dateValue.value = null
    loadTopics()
  },
  {immediate: true},
)

const close = () => {
  emit('update:visible', false)
}

const submit = async () => {
  errorMsg.value = ''

  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  /**
   * 分区列表进 confirm 之前先规范化。
   * allow-create 下用户可手输任意字符串；后端对空/缺省 partitions 的解释是「重置全部分区」，
   * 因此必须在确认前逐项校验，不合法直接阻断，确认文案使用规范化后的最终列表。
   * 注意 Number(' ')/Number('') === 0 能穿过 Number.isInteger && >=0，所以要先校验字符串形态。
   */
  const normalized = normalizePartitions(form.partitions)
  if (!normalized.ok) {
    errorMsg.value = normalized.error
    return
  }
  // 原始非空但过滤后为空 → 绝不把 [] 发出去（后端会解释为全部分区）
  if (form.partitions.length > 0 && normalized.partitions.length === 0) {
    errorMsg.value = t('topicDialogs.resetOffsets.errAllPartitionsInvalid')
    return
  }

  const scope =
    normalized.partitions.length > 0
      ? t('topicDialogs.resetOffsets.scopePartitions', {list: normalized.partitions.join(', ')})
      : t('topicDialogs.resetOffsets.scopeAllPartitions')

  const ok = await confirm(
    t('topicDialogs.resetOffsets.confirmMsg', {
      group: props.group,
      topic: form.topic,
      strategy: form.strategy,
      scope,
    }),
    t('topicDialogs.resetOffsets.confirmTitle'),
    'error',
  )
  if (!ok) return

  submitting.value = true
  try {
    const payload: {
      topic: string
      strategy: string
      partitions?: number[]
      offset?: number
      timestamp?: number
    } = {
      topic: form.topic,
      strategy: form.strategy,
    }
    if (normalized.partitions.length > 0) {
      payload.partitions = normalized.partitions
    }
    if (form.strategy === 'TO_OFFSET') payload.offset = form.offset
    if (form.strategy === 'TO_TIMESTAMP') payload.timestamp = form.timestamp

    const res = await resetOffsets(props.clusterId, props.group, payload)

    if (res.data.resetCount === 0) {
      ElMessage.warning(t('topicDialogs.resetOffsets.noneReset'))
    } else {
      ElMessage.success(t('topicDialogs.resetOffsets.resetDone', {n: res.data.resetCount}))
    }
    emit('updated')
    close()
  } catch (e: any) {
    // 40902 组非 Empty / 40401 Topic 不存在 / 40402 组不存在
    errorMsg.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.form-tip {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
