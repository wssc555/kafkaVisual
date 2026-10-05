<template>
  <!--
    首次启动引导:零集群时的全屏遮罩模态框。
    只负责「告知 + 分流」:添加集群 → 跳集群管理页自动打开新建对话框
    (?new=1);稍后再说 → 关闭本引导,用户可从侧边栏「集群管理」随时进入。
    show-close=false + 禁点遮罩/ESC 关闭:强制走两个显式按钮,避免误触绕过。
  -->
  <el-dialog
    :model-value="modelValue"
    width="480px"
    align-center
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    :title="t('app.firstRun.title')"
  >
    <div class="guide-body">
      <el-icon :size="44" class="guide-icon">
        <DataLine />
      </el-icon>
      <p class="guide-title">{{ t('app.firstRun.lead') }}</p>
      <p class="guide-text">
        {{ t('app.firstRun.text') }}
      </p>
      <p class="guide-hint">{{ t('app.firstRun.hint') }}</p>
    </div>
    <template #footer>
      <el-button @click="emit('later')">{{ t('common.later') }}</el-button>
      <el-button type="primary" @click="emit('add')">{{ t('app.firstRun.addCluster') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import {DataLine} from '@element-plus/icons-vue'
import {useI18n} from 'vue-i18n'

const {t} = useI18n()

defineProps<{modelValue: boolean}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'add'): void
  (e: 'later'): void
}>()
</script>

<style scoped>
.guide-body {
  text-align: center;
  padding: 4px 8px;
}
.guide-icon {
  color: var(--el-color-primary);
  margin-bottom: 12px;
}
.guide-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}
.guide-text {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
.guide-hint {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
