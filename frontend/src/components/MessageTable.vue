<template>
  <!--
    消息表(实时与归档共用,数据结构同 MessageRecord)。
    全列 min-width(窄档撑出横向滚动而不是挤压文字),
    详情入口列 fixed="right",value 预览全列 show-overflow-tooltip。
  -->
  <el-table :data="messages" v-loading="loading" stripe style="width: 100%" @row-click="(row: any) => emit('viewDetail', row)">
    <el-table-column label="Offset" min-width="90" prop="offset" />
    <el-table-column label="Timestamp" min-width="160">
      <template #default="{ row }">
        {{ new Date((row as any).timestamp).toLocaleString() }}
      </template>
    </el-table-column>
    <el-table-column label="Key" min-width="130" prop="key" show-overflow-tooltip />
    <el-table-column :label="t('topicDetail.messageTable.valuePreview')" min-width="250" show-overflow-tooltip>
      <template #default="{ row }">
        {{ (row as any).value?.substring(0, 100) }}
      </template>
    </el-table-column>
    <el-table-column :label="t('topicDetail.messageTable.headersCount')" align="center" min-width="90">
      <template #default="{ row }">
        {{ Object.keys((row as any).headers || {}).length }}
      </template>
    </el-table-column>
    <!-- 详情入口固定右侧:窄档横向滚动时保持可达 -->
    <el-table-column :label="t('topicDetail.messageTable.detail')" align="center" fixed="right" width="70">
      <template #default="{ row }">
        <el-button link size="small" type="primary" @click.stop="emit('viewDetail', row as MessageRecord)">
          {{ t('topicDetail.messageTable.view') }}
        </el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import {useI18n} from 'vue-i18n'
import type {MessageRecord} from '../api'

const {t} = useI18n()

defineProps<{ messages: MessageRecord[]; loading: boolean }>()
const emit = defineEmits<{ viewDetail: [msg: MessageRecord] }>()
</script>
