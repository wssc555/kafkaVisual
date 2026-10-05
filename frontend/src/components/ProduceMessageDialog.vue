<template>
  <el-dialog
    :model-value="visible"
    :title="t('topicDialogs.produce.title')"
    width="min(720px, 92%)"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <el-form :model="form" :rules="rules" ref="formRef" label-width="110px">
      <el-form-item label="Topic" prop="topic">
        <el-input v-model="form.topic" :placeholder="t('topicDialogs.produce.topicPlaceholder')" />
      </el-form-item>

      <el-form-item :label="t('topicDialogs.produce.partitionLabel')">
        <el-switch v-model="usePartition" />
        <el-input-number
          v-if="usePartition"
          v-model="form.partition"
          :min="0"
          :precision="0"
          :step="1"
          style="width: 160px; margin-left: 12px"
        />
        <span class="form-tip">
          {{ usePartition ? '' : t('topicDialogs.produce.partitionHint') }}
        </span>
      </el-form-item>

      <el-form-item label="Key">
        <el-input v-model="form.key" :placeholder="t('topicDialogs.produce.keyPlaceholder')" />
      </el-form-item>

      <el-form-item label="Value" prop="value">
        <el-input
          v-model="form.value"
          type="textarea"
          :rows="5"
          :placeholder="t('topicDialogs.produce.valuePlaceholder')"
        />
      </el-form-item>

      <el-form-item label="Headers">
        <HeadersEditor v-model="form.headers" />
      </el-form-item>

      <el-form-item :label="t('topicDialogs.produce.timestampLabel')">
        <el-switch v-model="useTimestamp" />
        <!--
          precision=0 是必须的：timestamp / partition 在 JSON body 里是 long / int，
          小数会被 Jackson 的 ACCEPT_FLOAT_AS_INT 静默截断（不报错、值变小），
          输入框层面直接禁掉小数比事后排查便宜得多。
        -->
        <el-input-number
          v-if="useTimestamp"
          v-model="form.timestamp"
          :min="0"
          :precision="0"
          :step="1"
          style="width: 200px; margin-left: 12px"
        />
        <span class="form-tip">
          {{ useTimestamp ? t('topicDialogs.produce.timestampHintOn') : t('topicDialogs.produce.timestampHintOff') }}
        </span>
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
      <el-button :loading="submitting" type="primary" @click="submit">{{ t('topicDialogs.produce.send') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {reactive, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {produceMessage, type ProduceResult} from '../api'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage} from 'element-plus'
import HeadersEditor from './HeadersEditor.vue'

const {t} = useI18n()

const props = defineProps<{
  visible: boolean
  /** 目标集群 id（多集群改造） */
  clusterId: number
  /** 默认 Topic（从 TopicDetail 进入时预填） */
  topic?: string
}>()

const emit = defineEmits<{
  'update:visible': [v: boolean]
  /** 发送成功，回传写入位置，父组件可据此跳到该 offset 查询 */
  produced: [result: ProduceResult]
}>()

const {confirm} = useCrudConfirm()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const errorMsg = ref('')

/**
 * partition / timestamp 是可选字段，缺省时后端走「按 key 哈希 / CreateTime」语义。
 * el-input-number 清空后会回填 min(0)，无法表达「未指定」，
 * 因此用独立开关控制是否发送该字段。
 */
const usePartition = ref(false)
const useTimestamp = ref(false)

const form = reactive<{
  topic: string
  partition: number | undefined
  key: string
  value: string
  headers: Record<string, string>
  timestamp: number | undefined
}>({
  topic: '',
  partition: undefined,
  key: '',
  value: '',
  headers: {},
  timestamp: undefined,
})

const rules: FormRules = {
  topic: [{required: true, message: t('topicDialogs.produce.ruleTopicRequired'), trigger: 'blur'}],
  value: [
    {
      // value @NotNull，但空串合法 → 只校验非 null
      validator: (_rule, value, callback) => {
        if (value === null || value === undefined) {
          callback(new Error(t('topicDialogs.produce.ruleValueNull')))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

watch(
  () => props.visible,
  (v) => {
    if (!v) return
    errorMsg.value = ''
    form.topic = props.topic || ''
    form.partition = undefined
    form.key = ''
    form.value = ''
    form.headers = {}
    form.timestamp = undefined
    usePartition.value = false
    useTimestamp.value = false
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

  const ok = await confirm(
    t('topicDialogs.produce.confirmSendMsg', {topic: form.topic}),
    t('topicDialogs.produce.confirmSendTitle'),
    'warning',
  )
  if (!ok) return

  submitting.value = true
  try {
    // 仅当字段有值时才放进请求体，让后端走缺省语义（按 key 哈希 / CreateTime）
    const payload: {
      topic: string
      value: string
      partition?: number
      key?: string
      timestamp?: number
      headers?: Record<string, string>
    } = {
      topic: form.topic,
      value: form.value,
    }
    if (usePartition.value && form.partition !== undefined && form.partition !== null) {
      payload.partition = form.partition
    }
    if (form.key) payload.key = form.key
    if (useTimestamp.value && form.timestamp !== undefined && form.timestamp !== null) {
      payload.timestamp = form.timestamp
    }
    if (Object.keys(form.headers || {}).length > 0) {
      payload.headers = form.headers
    }

    const res = await produceMessage(props.clusterId, payload)
    ElMessage.success(
      t('topicDialogs.produce.produced', {partition: res.data.partition, offset: res.data.offset}),
    )
    emit('produced', res.data)
    close()
  } catch (e: any) {
    // 40001 消息过大 / 40401 Topic 不存在：保留弹窗就地展示
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
