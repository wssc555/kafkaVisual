<template>
    <el-dialog :model-value="visible" :title="t('topicDialogs.createTopic.title')" width="min(720px, 92%)" @update:model-value="(v: any) => $emit('update:visible', v)">
    <el-form :model="form" :rules="rules" ref="formRef" label-width="120px">
      <el-form-item :label="t('topicDialogs.createTopic.nameLabel')" prop="name">
        <el-input v-model="form.name" :placeholder="t('topicDialogs.createTopic.namePlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('topicDialogs.createTopic.partitionsLabel')" prop="partitions">
        <el-input-number v-model="form.partitions" :min="1" />
      </el-form-item>
      <el-form-item :label="t('topicDialogs.createTopic.replicationLabel')" prop="replicationFactor">
        <el-input-number v-model="form.replicationFactor" :min="1" />
      </el-form-item>
      <el-form-item :label="t('topicDialogs.createTopic.configsLabel')">
        <el-input
          type="textarea"
          v-model="configText"
          :placeholder="t('topicDialogs.createTopic.configsPlaceholder')"
          :rows="3"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="close">{{ t('common.cancel') }}</el-button>
      <el-button :loading="submitting" type="primary" @click="submit">{{ t('topicDialogs.createTopic.create') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {reactive, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {createTopic, CreateTopicRequest} from '../api'
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'

const {t} = useI18n()

const props = defineProps<{
  visible: boolean
  /** 目标集群 id（多集群改造：创建动作发往激活集群） */
  clusterId: number
}>()
const emit = defineEmits<{ 'update:visible': [v: boolean]; created: [] }>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const configText = ref('')

const form = reactive<CreateTopicRequest>({
  name: '',
  partitions: 3,
  replicationFactor: 1,
})

const rules: FormRules = {
  name: [{ required: true, message: t('topicDialogs.createTopic.ruleNameRequired'), trigger: 'blur' }],
  partitions: [{ required: true, message: t('topicDialogs.createTopic.rulePartitionsRequired'), trigger: 'change' }],
  replicationFactor: [{ required: true, message: t('topicDialogs.createTopic.ruleReplicationRequired'), trigger: 'change' }],
}

const close = () => {
  emit('update:visible', false)
}

const submit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  // 危险操作二次确认
  try {
    await ElMessageBox.confirm(
      t('topicDialogs.createTopic.confirmMsg', {
        name: form.name,
        partitions: form.partitions,
        replication: form.replicationFactor,
      }),
      t('topicDialogs.createTopic.confirmTitle'),
      {confirmButtonText: t('topicDialogs.createTopic.create'), cancelButtonText: t('common.cancel'), type: 'warning'},
    )
  } catch {
    return
  }
  submitting.value = true
  try {
    // 解析 configText
    const configs: Record<string, string> = {}
    for (const line of configText.value.split('\n')) {
      const trimmed = line.trim()
      if (!trimmed) continue
      const eqIdx = trimmed.indexOf('=')
      if (eqIdx > 0) {
        configs[trimmed.substring(0, eqIdx).trim()] = trimmed.substring(eqIdx + 1).trim()
      }
    }
    if (Object.keys(configs).length > 0) {
      form.configs = configs
    }

    await createTopic(props.clusterId, form)
    ElMessage.success(t('topicDialogs.createTopic.created', {name: form.name}))
    emit('created')
    close()
  } catch (e: any) {
    ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

watch(() => props.visible, (v) => {
  if (v) {
    form.name = ''
    form.partitions = 3
    form.replicationFactor = 1
    configText.value = ''
    delete form.configs
  }
})
</script>