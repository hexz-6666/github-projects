<script setup>
import { computed } from 'vue'

const props = defineProps({
  batches: { type: Array, default: () => [] },
})

const maxRows = computed(() => {
  let max = 1
  for (const batch of props.batches) {
    max = Math.max(max, Number(batch.acceptedRows) || 0, Number(batch.rejectedRows) || 0)
  }
  return max
})

const statusLabels = {
  SUCCEEDED: '成功',
  PARTIAL: '部分成功',
  FAILED: '失败',
}

function width(value) {
  return `${Math.max(0, (Number(value) || 0) / maxRows.value * 100)}%`
}

function statusLabel(status) {
  return statusLabels[status] || status
}

function statusClass(status) {
  return `status-${String(status || '').toLowerCase()}`
}
</script>

<template>
  <div class="import-chart">
    <div
      v-for="batch in batches"
      :key="batch.batchId"
      class="import-batch"
      :class="statusClass(batch.status)"
      :aria-label="`${batch.sourceSystem} ${batch.batchId} ${statusLabel(batch.status)}`"
    >
      <div class="import-batch__head">
        <strong>{{ batch.sourceSystem }} · {{ batch.batchId }}</strong>
        <span class="import-status" :class="`import-status--${statusClass(batch.status)}`">
          {{ statusLabel(batch.status) }}
        </span>
      </div>
      <div class="import-row">
        <span>接收</span>
        <div class="import-track"><i :style="{ width: width(batch.acceptedRows) }"></i></div>
        <b>{{ batch.acceptedRows }} 行</b>
      </div>
      <div class="import-row">
        <span>拒绝</span>
        <div class="import-track"><i class="rejected" :style="{ width: width(batch.rejectedRows) }"></i></div>
        <b>{{ batch.rejectedRows }} 行</b>
      </div>
    </div>
    <details class="chart-data">
      <summary>查看批次数据表</summary>
      <div class="table-wrap">
        <table>
          <thead><tr><th>来源</th><th>批次</th><th>状态</th><th>接收行</th><th>拒绝行</th></tr></thead>
          <tbody>
            <tr v-for="batch in batches" :key="`row-${batch.batchId}`">
              <td>{{ batch.sourceSystem }}</td>
              <td>{{ batch.batchId }}</td>
              <td>{{ statusLabel(batch.status) }}</td>
              <td>{{ batch.acceptedRows }}</td>
              <td>{{ batch.rejectedRows }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </div>
</template>
