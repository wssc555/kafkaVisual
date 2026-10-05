<template>
  <el-dialog
    :model-value="visible"
    :title="t('topicDialogs.expand.title', {topic})"
    width="min(720px, 92%)"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
  >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="t('topicDialogs.expand.notice')"
      style="margin-bottom: 16px"
    />

    <el-form label-width="140px">
      <el-form-item :label="t('topicDialogs.expand.currentLabel')">
        <el-tag type="info">{{ currentPartitions }}</el-tag>
      </el-form-item>
      <el-form-item :label="t('topicDialogs.expand.targetLabel')">
        <el-input-number
          v-model="target"
          :min="1"
          :max="100000"
          style="width: 200px"
        />
      </el-form-item>
      <el-form-item v-if="target > currentPartitions" :label="t('topicDialogs.expand.addedLabel')">
        <el-tag type="warning">+{{ target - currentPartitions }}</el-tag>
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
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="submit"
      >
        {{ t('topicDialogs.expand.submit') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {expandPartitions} from '../api'
import {useCrudConfirm} from '../composables/useCrudConfirm'
import {ElMessage} from 'element-plus'

const {t} = useI18n()

const props = defineProps<{
  visible: boolean
  /** 目标集群 id（多集群改造） */
  clusterId: number
  topic: string
  /** 当前分区数，由父组件从 TopicDetail 传入 */
  currentPartitions: number
}>()

const emit = defineEmits<{
  'update:visible': [v: boolean]
  updated: []
}>()

const {confirm} = useCrudConfirm()

const target = ref(1)
const submitting = ref(false)
const errorMsg = ref('')

// 打开时默认填「当前 + 1」，避免用户面对空白输入框
watch(
  () => props.visible,
  (v) => {
    if (!v) return
    errorMsg.value = ''
    target.value = Math.max(1, props.currentPartitions + 1)
  },
  {immediate: true},
)

// 目标数必须大于当前数，否则后端返回 400/40001；前端直接禁用按钮给出即时反馈
const canSubmit = computed(
  () => !submitting.value && target.value > props.currentPartitions,
)

const close = () => {
  emit('update:visible', false)
}

const submit = async () => {
  errorMsg.value = ''

  const ok = await confirm(
    t('topicDialogs.expand.confirmMsg', {topic: props.topic, from: props.currentPartitions, to: target.value}),
    t('topicDialogs.expand.confirmTitle'),
    'error',
  )
  if (!ok) return

  submitting.value = true
  try {
    const res = await expandPartitions(props.clusterId, props.topic, target.value)
    ElMessage.success(
      t('topicDialogs.expand.done', {n: res.data.partitions ?? target.value}),
    )
    emit('updated')
    close()
  } catch (e: any) {
    errorMsg.value = e.message
  } finally {
    submitting.value = false
  }
}
</script>
