<script setup>
import { computed } from 'vue'

const props = defineProps({
  items: { type: Array, default: () => [] },
  label: { type: String, default: '分布' },
})

const radius = 72
const circumference = 2 * Math.PI * radius
const total = computed(() => props.items.reduce((sum, item) => sum + (Number(item.count) || 0), 0))

function fraction(item) {
  return total.value > 0 ? (Number(item.count) || 0) / total.value : 0
}

function dash(item) {
  return `${fraction(item) * circumference} ${circumference}`
}

function offset(index) {
  let before = 0
  for (let i = 0; i < index; i++) {
    before += fraction(props.items[i])
  }
  return -before * circumference
}

function percent(item) {
  return total.value > 0 ? `${(fraction(item) * 100).toFixed(1)}%` : '0%'
}
</script>

<template>
  <div class="chart-wrap donut-wrap">
    <svg class="chart-svg donut-svg" viewBox="0 0 220 220" role="img" :aria-label="`${label}环图`">
      <circle class="donut-track" cx="110" cy="110" :r="radius" />
      <circle
        v-for="(item, index) in items"
        :key="item.key"
        class="donut-segment"
        cx="110"
        cy="110"
        :r="radius"
        :stroke="item.color"
        :stroke-dasharray="dash(item)"
        :stroke-dashoffset="offset(index)"
      >
        <title>{{ item.label }}：{{ item.count }}（{{ percent(item) }}）</title>
      </circle>
      <text x="110" y="105" text-anchor="middle" class="donut-total">{{ total }}</text>
      <text x="110" y="126" text-anchor="middle" class="donut-total-label">当前总数</text>
    </svg>
    <div class="chart-legend" aria-label="图例">
      <span v-for="item in items" :key="`legend-${item.key}`" class="chart-legend__item">
        <i :style="{ background: item.color }"></i>{{ item.label }} · {{ item.count }}（{{ percent(item) }}）
      </span>
    </div>
    <details class="chart-data">
      <summary>查看数据表</summary>
      <div class="table-wrap">
        <table>
          <thead><tr><th>类别</th><th>数量</th><th>比例</th></tr></thead>
          <tbody>
            <tr v-for="item in items" :key="`row-${item.key}`">
              <td>{{ item.label }}</td><td>{{ item.count }}</td><td>{{ percent(item) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </div>
</template>
