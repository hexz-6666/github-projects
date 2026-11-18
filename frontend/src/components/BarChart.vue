<script setup>
import { computed } from 'vue'

const props = defineProps({
  items: { type: Array, default: () => [] },
  label: { type: String, default: '分布' },
  unit: { type: String, default: '笔' },
  rotateLabels: { type: Boolean, default: true },
})

const width = 720
const height = 280
const left = 34
const right = 16
const top = 18
const bottom = 76

const plotWidth = computed(() => width - left - right)
const plotHeight = computed(() => height - top - bottom)
const maxValue = computed(() => {
  let max = 1
  for (const item of props.items) {
    max = Math.max(max, Number(item.value) || 0)
  }
  return max
})
const barStep = computed(() => props.items.length ? plotWidth.value / props.items.length : plotWidth.value)
const barWidth = computed(() => Math.max(5, Math.min(28, barStep.value * 0.58)))

function barHeight(value) {
  return (Number(value) || 0) / maxValue.value * plotHeight.value
}

function barX(index) {
  return left + index * barStep.value + (barStep.value - barWidth.value) / 2
}

function shortLabel(value) {
  const text = String(value || '')
  return text.length > 14 ? `${text.slice(0, 12)}…` : text
}
</script>

<template>
  <div class="chart-wrap">
    <svg class="chart-svg" :viewBox="`0 0 ${width} ${height}`" role="img" :aria-label="`${label}柱状图，单位${unit}`">
      <g class="chart-grid">
        <line :x1="left" :x2="width - right" :y1="top" :y2="top" />
        <line :x1="left" :x2="width - right" :y1="top + plotHeight" :y2="top + plotHeight" />
        <text :x="left - 7" :y="top + 4" text-anchor="end">{{ maxValue }}</text>
        <text :x="left - 7" :y="top + plotHeight + 4" text-anchor="end">0</text>
      </g>
      <g v-for="(item, index) in items" :key="`${item.label}-${index}`">
        <rect
          :x="barX(index)"
          :y="top + plotHeight - barHeight(item.value)"
          :width="barWidth"
          :height="barHeight(item.value)"
          :fill="item.color"
          rx="3"
        >
          <title>{{ item.label }}：{{ item.value }} {{ unit }}</title>
        </rect>
        <text
          :x="barX(index) + barWidth / 2"
          :y="top + plotHeight - barHeight(item.value) - 5"
          text-anchor="middle"
          class="bar-value"
        >{{ item.value }}</text>
        <text
          :x="barX(index) + barWidth / 2"
          :y="rotateLabels ? height - 52 : height - 26"
          :text-anchor="rotateLabels ? 'end' : 'middle'"
          :transform="rotateLabels
            ? `rotate(-35 ${barX(index) + barWidth / 2} ${height - 52})`
            : undefined"
          class="bar-label"
        >{{ shortLabel(item.label) }}</text>
      </g>
    </svg>
    <div class="chart-legend" aria-label="图例">
      <span v-for="(item, index) in items" :key="`legend-${index}`" class="chart-legend__item">
        <i :style="{ background: item.color }"></i>{{ item.label }}
      </span>
    </div>
    <details class="chart-data">
      <summary>查看数据表</summary>
      <div class="table-wrap">
        <table>
          <thead><tr><th>类别</th><th>数量</th></tr></thead>
          <tbody>
            <tr v-for="(item, index) in items" :key="`row-${index}`">
              <td>{{ item.label }}</td><td>{{ item.value }} {{ unit }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </div>
</template>
