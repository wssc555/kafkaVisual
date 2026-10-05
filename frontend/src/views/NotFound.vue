<template>
  <el-empty
    :description="t('app.notFound.description')"
    :image-size="200"
  >
    <el-button type="primary" @click="$router.push('/')">{{ t('app.notFound.back') }}</el-button>
  </el-empty>
</template>

<script setup lang="ts">
import {useI18n} from 'vue-i18n'

const {t} = useI18n()
// 由 router/index.ts 的 catch-all 路由渲染；无需额外逻辑。
</script>