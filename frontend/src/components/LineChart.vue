<script setup>
import { computed } from 'vue'

const props = defineProps({
  buckets: { type: Array, default: () => [] },
  series: { type: Array, default: () => [] },
  unit: { type: String, default: '笔' },
  label: { type: String, default: '趋势' },
})

const width = 720
const height = 280
const left = 34
const right = 16
const top = 18
const bottom = 38

const maxValue = computed(() => {
  let max = 1
  for (const item of props.series) {
    for (const value of item.points || []) {
      max = Math.max(max, Number(value) || 0)
    }
  }
  return max
})

const plotWidth = computed(() => width - left - right)
const plotHeight = computed(() => height - top - bottom)
const x = computed(() => (index) => {
  if (props.buckets.length <= 1) return left
  return left + (index / (props.buckets.length - 1)) * plotWidth.value
})
const y = computed(() => (value) => top + plotHeight.value - (Number(value) || 0) / maxValue.value * plotHeight.value)
const yTicks = computed(() => [0, 0.25, 0.5, 0.75, 1].map((fraction) => ({
  value: Math.round(maxValue.value * fraction),
  y: top + plotHeight.value - fraction * plotHeight.value,
})))
const labelIndexes = computed(() => {
  if (!props.buckets.length) return []
  if (props.buckets.length <= 4) return props.buckets.map((_, index) => index)
  return [0, Math.floor((props.buckets.length - 1) / 2), props.buckets.length - 1]
})

function linePoints(points) {
  return (points || []).map((value, index) => `${x.value(index)},${y.value(value)}`).join(' ')
}

function bucketLabel(index) {
  const bucket = props.buckets[index]
  if (!bucket) return ''
  return bucket.start.slice(5) + (bucket.partial ? '*' : '')
}

function tableValue(item, index) {
  return Number((item.points || [])[index] || 0)
}
</script>

<template>
  <div class="chart-wrap">
    <svg
      class="chart-svg"
      :viewBox="`0 0 ${width} ${height}`"
      role="img"
      :aria-label="`${label}折线图，UTC 时间，单位${unit}`"
    >
      <g class="chart-grid">
        <line
          v-for="tick in yTicks"
          :key="`grid-${tick.y}`"
          :x1="left"
          :x2="width - right"
          :y1="tick.y"
          :y2="tick.y"
        />
        <text
          v-for="tick in yTicks"
          :key="`tick-${tick.y}`"
          :x="left - 7"
          :y="tick.y + 4"
          text-anchor="end"
        >{{ tick.value }}</text>
      </g>
      <g class="chart-axis">
        <line :x1="left" :x2="left" :y1="top" :y2="top + plotHeight" />
        <line :x1="left" :x2="width - right" :y1="top + plotHeight" :y2="top + plotHeight" />
        <text
          v-for="index in labelIndexes"
          :key="`xlabel-${index}`"
          :x="x(index)"
          :y="height - 14"
          text-anchor="middle"
        >{{ bucketLabel(index) }}</text>
      </g>
      <g v-for="item in series" :key="item.label">
        <polyline
          fill="none"
          :stroke="item.color"
          stroke-width="2.5"
          stroke-linejoin="round"
          stroke-linecap="round"
          :points="linePoints(item.points)"
        />
        <circle
          v-for="(value, index) in item.points"
          :key="`${item.label}-${index}`"
          :cx="x(index)"
          :cy="y(value)"
          r="3.2"
          :fill="item.color"
        >
          <title>{{ bucketLabel(index) }} · {{ item.label }}：{{ value }} {{ unit }}</title>
        </circle>
      </g>
    </svg>
    <div class="chart-legend" aria-label="图例">
      <span v-for="item in series" :key="`legend-${item.label}`" class="chart-legend__item">
        <i :style="{ background: item.color }"></i>{{ item.label }}
      </span>
    </div>
    <details class="chart-data">
      <summary>查看数据表</summary>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>UTC bucket</th>
              <th v-for="item in series" :key="`th-${item.label}`">{{ item.label }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(bucket, index) in buckets" :key="`row-${bucket.start}`">
              <td>{{ bucket.start }}{{ bucket.partial ? '（部分）' : '' }}</td>
              <td v-for="item in series" :key="`cell-${item.label}-${index}`">
                {{ tableValue(item, index) }} {{ unit }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </div>
</template>
