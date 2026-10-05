<template>
  <!--
    消费组分区进度表:组内 topic tab 与单 topic 快查共用。
    数值列 min-width;Member/Client/Host 是排障细节,窄档(v-if)隐藏。
  -->
  <el-table :data="partitions" stripe size="small">
    <el-table-column prop="partition" label="Partition" width="90" />
    <el-table-column label="Current Offset" min-width="130">
      <template #default="{ row }">{{ row.currentOffset.toLocaleString() }}</template>
    </el-table-column>
    <el-table-column label="Log End Offset" min-width="140">
      <template #default="{ row }">{{ row.logEndOffset.toLocaleString() }}</template>
    </el-table-column>
    <el-table-column label="Lag" min-width="100">
      <template #default="{ row }">
        <el-tag :type="row.lag > 0 ? 'warning' : 'success'" size="small">
          {{ row.lag.toLocaleString() }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column v-if="!isNarrow" label="Member ID" min-width="200" show-overflow-tooltip>
      <template #default="{ row }">{{ row.memberId || '-' }}</template>
    </el-table-column>
    <el-table-column v-if="!isNarrow" label="Client ID" min-width="160" show-overflow-tooltip>
      <template #default="{ row }">{{ row.clientId || '-' }}</template>
    </el-table-column>
    <el-table-column v-if="!isNarrow" label="Host" min-width="140" show-overflow-tooltip>
      <template #default="{ row }">{{ row.host || '-' }}</template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import type {PartitionOffset} from '../api'
import {useResponsive} from '../composables/useResponsive'

/** 消费组分区进度表：组内 topic tab 与单 topic 快查共用 */
defineProps<{
  partitions: PartitionOffset[]
}>()

const {isNarrow} = useResponsive()
</script>
