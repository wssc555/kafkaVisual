<template>
  <el-dialog
    :model-value="modelValue"
    :title="editing ? t('inspection.rules.edit') : t('inspection.rules.create')"
    width="760px"
    @update:model-value="onVisibleChange"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="120px">
      <el-form-item :label="t('inspection.rules.name')" prop="name">
        <el-input v-model="form.name" maxlength="128" />
      </el-form-item>

      <el-form-item :label="t('inspection.rules.severity')" prop="severity">
        <el-select v-model="form.severity" style="width: 160px">
          <el-option label="INFO" value="INFO" />
          <el-option label="WARN" value="WARN" />
          <el-option label="CRITICAL" value="CRITICAL" />
        </el-select>
      </el-form-item>

      <el-form-item :label="t('inspection.rules.scope')" prop="metricScope">
        <el-select v-model="form.metricScope" style="width: 200px" @change="onScopeChange">
          <el-option v-for="s in scopes" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>

      <el-form-item :label="t('inspection.rules.scopeFilter')">
        <el-input v-model="form.scopeFilter" :placeholder="t('inspection.rules.scopeFilterHint')" maxlength="256" />
      </el-form-item>

      <el-form-item :label="t('inspection.rules.cron')" prop="cronExpr">
        <el-input v-model="form.cronExpr" :placeholder="t('inspection.rules.cronHint')" />
      </el-form-item>

      <el-form-item :label="t('inspection.rules.condition')">
        <div class="rb-conditions">
          <div class="rb-combinator">
            <el-radio-group v-model="form.combinator">
              <el-radio-button value="AND">{{ t('inspection.rules.and') }}</el-radio-button>
              <el-radio-button value="OR">{{ t('inspection.rules.or') }}</el-radio-button>
            </el-radio-group>
          </div>

          <div v-for="(cond, idx) in form.conditions" :key="idx" class="rb-row">
            <el-select
              v-model="cond.metric"
              :placeholder="t('inspection.rules.metric')"
              class="rb-metric"
              filterable
            >
              <el-option
                v-for="m in scopedMetrics"
                :key="m.key"
                :label="m.key"
                :value="m.key"
              >
                <span>{{ m.key }}</span>
                <span class="rb-metric-desc">{{ m.description }}</span>
              </el-option>
            </el-select>

            <el-select v-model="cond.operator" class="rb-op">
              <el-option v-for="op in operators" :key="op" :label="op" :value="op" />
            </el-select>

            <el-input v-model="cond.threshold" :placeholder="t('inspection.rules.threshold')" class="rb-threshold" />

            <el-select v-model="cond.windowEnabled" class="rb-window">
              <el-option :label="t('inspection.rules.windowNone')" :value="false" />
              <el-option :label="t('inspection.rules.window')" :value="true" />
            </el-select>

            <template v-if="cond.windowEnabled">
              <el-input-number v-model="cond.windowCount" :max="100" :min="1" class="rb-count" />
              <el-select v-model="cond.windowMatch" class="rb-match">
                <el-option :label="t('inspection.rules.matchAll')" value="ALL" />
                <el-option :label="t('inspection.rules.matchAny')" value="ANY" />
              </el-select>
            </template>

            <el-button
              :disabled="form.conditions.length <= 1"
              :icon="DeleteIcon"
              text
              type="danger"
              @click="removeCondition(idx)"
            />
          </div>

          <el-button :icon="PlusIcon" size="small" text type="primary" @click="addCondition">
            {{ t('inspection.rules.addCondition') }}
          </el-button>
        </div>
      </el-form-item>

      <el-form-item :label="t('inspection.rules.channels')" prop="channelIds">
        <el-select
          v-model="form.channelIds"
          :placeholder="t('inspection.rules.channelsHint')"
          class="rb-channels"
          filterable
          multiple
        >
          <el-option v-for="ch in channels" :key="ch.id" :label="ch.name" :value="ch.id" />
        </el-select>
      </el-form-item>

      <el-form-item :label="t('inspection.rules.description')">
        <el-input v-model="form.description" :rows="2" maxlength="512" type="textarea" />
      </el-form-item>

      <el-form-item :label="t('inspection.rules.enabled')">
        <el-switch v-model="form.enabled" />
      </el-form-item>

      <el-form-item v-if="nextFireAt" :label="t('inspection.rules.nextFire')">
        <span>{{ formatTime(nextFireAt) }}</span>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="onVisibleChange(false)">{{ t('common.cancel') }}</el-button>
      <el-button :loading="submitting" type="primary" @click="submit">{{ t('common.confirm') }}</el-button>
    </template>
  </el-dialog>
</template>

<script lang="ts" setup>
import {computed, ref, watch} from 'vue'
import {useI18n} from 'vue-i18n'
import {Delete as DeleteIcon, Plus as PlusIcon} from '@element-plus/icons-vue'
import {ElMessage, type FormInstance, type FormRules} from 'element-plus'
import type {
  AlertChannel,
  CreateRulePayload,
  InspectionRule,
  MetricCatalogItem,
  MetricScopeValue,
  RuleConditionSpec,
} from '../../api/inspection'

/** 表单内的条件行：窗口开关与窗口参数拆成平铺字段，提交时再还原成 DSL 形态 */
interface ConditionRow {
  metric: string
  operator: string
  threshold: string
  windowEnabled: boolean
  windowCount: number
  windowMatch: 'ALL' | 'ANY'
}

const props = defineProps<{
  modelValue: boolean
  clusterId: number
  catalog: MetricCatalogItem[]
  channels: AlertChannel[]
  /** 传入即为编辑态 */
  rule?: InspectionRule | null
  /** 创建/更新响应带回的下次执行时间（前端不做 cron 解析） */
  nextFireAt?: string | null
  submitting?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', visible: boolean): void
  (e: 'submit', payload: CreateRulePayload): void
}>()

const {t} = useI18n()
const formRef = ref<FormInstance | null>(null)

const scopes = computed<MetricScopeValue[]>(() => {
  const set = new Set<string>()
  for (const item of props.catalog) set.add(item.scope)
  return Array.from(set) as MetricScopeValue[]
})

const operators = ['>', '>=', '<', '<=', '==', '!=']

const emptyCondition = (scope: MetricScopeValue): ConditionRow => {
  const first = props.catalog.find((m) => m.scope === scope)
  return {
    metric: first ? first.key : '',
    operator: '>',
    threshold: '0',
    windowEnabled: false,
    windowCount: 3,
    windowMatch: 'ALL',
  }
}

const form = ref<{
  name: string
  severity: 'INFO' | 'WARN' | 'CRITICAL'
  metricScope: MetricScopeValue
  scopeFilter: string
  cronExpr: string
  combinator: 'AND' | 'OR'
  conditions: ConditionRow[]
  channelIds: number[]
  description: string
  enabled: boolean
}>({
  name: '',
  severity: 'WARN',
  metricScope: 'CLUSTER',
  scopeFilter: '',
  cronExpr: '0 0/1 * * * ?',
  combinator: 'AND',
  conditions: [emptyCondition('CLUSTER')],
  channelIds: [],
  description: '',
  enabled: true,
})

const editing = computed(() => Boolean(props.rule))

const scopedMetrics = computed(() => props.catalog.filter((m) => m.scope === form.value.metricScope))

// computed:locale 切换后校验消息跟随翻译,而不是停在挂载时的那份
const rules = computed<FormRules>(() => ({
  name: [{required: true, message: t('inspection.rules.required'), trigger: 'blur'}],
  cronExpr: [{required: true, message: t('inspection.rules.required'), trigger: 'blur'}],
  channelIds: [{required: true, message: t('inspection.rules.required'), trigger: 'change'}],
}))

/** 后端时间列统一是 UTC ISO 串(带 Z);转本地时区可读格式展示。 */
const formatTime = (value?: string | null) => {
  if (!value) return '-'
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString()
}

/** 指标下拉随 scope 联动：切到别的 scope 后旧指标键必然非法，直接重置条件 */
const onScopeChange = () => {
  for (let i = 0; i < form.value.conditions.length; i++) {
    form.value.conditions[i] = emptyCondition(form.value.metricScope)
  }
}

const addCondition = () => {
  form.value.conditions.push(emptyCondition(form.value.metricScope))
}

const removeCondition = (idx: number) => {
  form.value.conditions.splice(idx, 1)
}

const onVisibleChange = (visible: boolean) => {
  emit('update:modelValue', visible)
}

/** 打开对话框时按编辑态回填（含 conditionJson 反解） */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    const rule = props.rule
    if (!rule) {
      form.value = {
        name: '',
        severity: 'WARN',
        metricScope: scopes.value[0] || 'CLUSTER',
        scopeFilter: '',
        cronExpr: '0 0/1 * * * ?',
        combinator: 'AND',
        conditions: [emptyCondition(scopes.value[0] || 'CLUSTER')],
        channelIds: [],
        description: '',
        enabled: true,
      }
      return
    }
    let combinator: 'AND' | 'OR' = 'AND'
    let parsed: {metric: string; operator: string; threshold: string; window?: {count: number; match: 'ALL' | 'ANY'}}[] = []
    try {
      const raw = JSON.parse(rule.conditionJson || '{}')
      combinator = raw.combinator === 'OR' ? 'OR' : 'AND'
      parsed = Array.isArray(raw.conditions) ? raw.conditions : []
    } catch {
      parsed = []
    }
    form.value = {
      name: rule.name,
      severity: rule.severity,
      metricScope: rule.metricScope,
      scopeFilter: rule.scopeFilter || '',
      cronExpr: rule.cronExpr,
      combinator,
      conditions: parsed.length
        ? parsed.map((c) => ({
            metric: c.metric,
            operator: c.operator,
            threshold: String(c.threshold),
            windowEnabled: Boolean(c.window),
            windowCount: c.window?.count || 3,
            windowMatch: c.window?.match || 'ALL',
          }))
        : [emptyCondition(rule.metricScope)],
      channelIds: rule.channelIds || [],
      description: rule.description || '',
      enabled: rule.enabled,
    }
  },
  {immediate: true},
)

const buildConditions = (): RuleConditionSpec[] =>
  form.value.conditions.map((c) => ({
    metric: c.metric,
    operator: c.operator,
    threshold: c.threshold,
    window: c.windowEnabled ? {count: c.windowCount, match: c.windowMatch} : null,
  }))

const submit = async () => {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  if (form.value.conditions.length === 0) {
    ElMessage.warning(t('inspection.rules.noCondition'))
    return
  }
  const payload: CreateRulePayload = {
    name: form.value.name,
    severity: form.value.severity,
    metricScope: form.value.metricScope,
    scopeFilter: form.value.scopeFilter || null,
    combinator: form.value.combinator,
    conditions: buildConditions(),
    cronExpr: form.value.cronExpr,
    channelIds: form.value.channelIds,
    description: form.value.description || null,
    enabled: form.value.enabled,
  }
  emit('submit', payload)
}
</script>

<style scoped>
.rb-conditions {
  width: 100%;
}
.rb-combinator {
  margin-bottom: 8px;
}
.rb-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.rb-metric {
  width: 220px;
}
.rb-op {
  width: 78px;
}
.rb-threshold {
  width: 110px;
}
.rb-window {
  width: 110px;
}
.rb-count {
  width: 110px;
}
.rb-match {
  width: 120px;
}
.rb-channels {
  width: 100%;
}
.rb-metric-desc {
  float: right;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
