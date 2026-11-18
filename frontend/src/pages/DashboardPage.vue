<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, formatPercent } from '../api.js'
import BarChart from '../components/BarChart.vue'
import ChartFrame from '../components/ChartFrame.vue'
import DonutChart from '../components/DonutChart.vue'
import ImportBatchChart from '../components/ImportBatchChart.vue'
import LineChart from '../components/LineChart.vue'

const data = ref(null)
const loading = ref(true)
const error = ref('')
const windowDays = ref(366)
const bucket = ref('week')

const rangeOptions = [
  { days: 7, label: '近 7 天', bucket: 'day' },
  { days: 30, label: '近 30 天', bucket: 'day' },
  { days: 90, label: '近 90 天', bucket: 'day' },
  { days: 366, label: '近 366 天', bucket: 'week' },
]

const originColors = {
  OLIST: '#1f7f5d',
  SYNTHETIC: '#a66c16',
  UNKNOWN: '#738179',
}
const riskColors = { LOW: '#5b8f78', MEDIUM: '#a66c16', HIGH: '#b84b4b' }
const statusColors = {
  OPEN: '#5b8f78',
  ASSIGNED: '#3f7e9c',
  IN_PROGRESS: '#a66c16',
  RESOLVED: '#1f7f5d',
  CANCELLED: '#738179',
}
const reviewColors = { CONFIRMED: '#1f7f5d', REJECTED: '#b84b4b', INCONCLUSIVE: '#a66c16' }

const buckets = computed(() => data.value?.buckets || [])
const orderSeries = computed(() => (data.value?.orders?.series || []).map((item) => ({
  label: `订单 · ${item.origin}`,
  color: originColors[item.origin] || originColors.UNKNOWN,
  points: item.points || [],
})))
const eventSeries = computed(() => {
  const source = data.value
  if (!source) return []
  return [
    { label: '风险告警', color: '#b84b4b', points: source.alerts?.points || [] },
    { label: '新建工单', color: '#3f7e9c', points: source.workOrders?.points || [] },
    { label: '复核推进至已解决', color: '#a66c16', points: source.reviews?.points || [] },
  ]
})
const riskItems = computed(() => (data.value?.snapshot?.alertsByRiskLevel || []).map((item) => ({
  ...item,
  label: { LOW: '低', MEDIUM: '中', HIGH: '高' }[item.key] || item.key,
  color: riskColors[item.key] || '#738179',
})))
const statusItems = computed(() => (data.value?.snapshot?.workOrdersByStatus || []).map((item) => ({
  ...item,
  value: item.count,
  label: {
    OPEN: '待处理',
    ASSIGNED: '已指派',
    IN_PROGRESS: '处理中',
    RESOLVED: '已解决',
    CANCELLED: '已取消',
  }[item.key] || item.key,
  color: statusColors[item.key] || '#738179',
})))
const reviewItems = computed(() => (data.value?.reviews?.byResult || []).map((item) => ({
  ...item,
  value: item.count,
  label: { CONFIRMED: '确认', REJECTED: '排除', INCONCLUSIVE: '不确定' }[item.key] || item.key,
  color: reviewColors[item.key] || '#738179',
})))
const dq = computed(() => data.value?.dq || {})
const alertsSnapshotHasData = computed(() => Boolean(
  data.value?.snapshot?.alertsHasData ?? data.value?.snapshot?.hasData,
))
const workOrdersSnapshotHasData = computed(() => Boolean(
  data.value?.snapshot?.workOrdersHasData ?? data.value?.snapshot?.hasData,
))
const eventHasData = computed(() => {
  const source = data.value
  return Boolean(source?.alerts?.hasData || source?.workOrders?.hasData || source?.reviews?.hasData)
})

function utcLabel(value) {
  return value ? `${value.slice(0, 16).replace('T', ' ')} UTC` : '—'
}

function setWindow(option) {
  windowDays.value = option.days
  bucket.value = option.bucket
  load()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const end = new Date()
    const start = new Date(end.getTime() - windowDays.value * 24 * 60 * 60 * 1000)
    const params = new URLSearchParams({
      from: start.toISOString(),
      to: end.toISOString(),
      bucket: bucket.value,
    })
    data.value = await api(`/api/v3/dashboard/overview?${params.toString()}`)
  } catch (requestError) {
    error.value = requestError.message || '看板加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <header class="page-head dashboard-head">
      <div>
        <p class="eyebrow">ADMIN · 运营聚合</p>
        <h1>效能总览</h1>
        <p class="muted">
          仅聚合订单/告警/工单/复核/导入/DQ；风险分不是欺诈概率，告警不是欺诈事实，复核不是客观真值。
          IEEE-CIS 模型评测不在本页，也不与订单数据拼接。
        </p>
      </div>
      <div class="dashboard-actions">
        <div class="range-switch" role="group" aria-label="时间范围">
          <button
            v-for="option in rangeOptions"
            :key="option.days"
            type="button"
            :class="['range-button', { active: windowDays === option.days }]"
            @click="setWindow(option)"
          >{{ option.label }}</button>
        </div>
        <button type="button" class="ghost" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <p v-if="error" class="error-box" role="alert">{{ error }}</p>
    <div v-if="loading" class="state" role="status">正在聚合看板数据…</div>

    <template v-else-if="data">
      <p class="dashboard-range-note" role="status">
        范围：{{ utcLabel(data.range.from) }} 至 {{ utcLabel(data.range.to) }} ·
        {{ data.range.bucket === 'week' ? 'ISO 周' : 'UTC 日' }} · 最大 366 天 · 只读
      </p>

      <div class="kpi-grid" aria-label="范围内汇总">
        <article class="kpi-card">
          <span>范围内订单</span><strong>{{ data.totals.orders }}</strong><small>只计数量，不汇总金额</small>
        </article>
        <article class="kpi-card">
          <span>范围内风险告警</span><strong>{{ data.totals.alerts }}</strong><small>不是欺诈事实</small>
        </article>
        <article class="kpi-card">
          <span>范围内新建工单</span><strong>{{ data.totals.newWorkOrders }}</strong><small>按 created_at</small>
        </article>
        <article class="kpi-card">
          <span>复核推进至已解决</span><strong>{{ data.totals.reviewResolved }}</strong><small>次数，不是工单完成量</small>
        </article>
      </div>

      <div class="dashboard-grid">
        <ChartFrame
          title="订单量趋势"
          note="按实际 data_origin 分开；OLIST 与 SYNTHETIC 不相加；不展示跨币种金额。"
          :has-data="Boolean(data.orders.hasData)"
          empty-text="当前范围没有订单；可切换到近 366 天查看演示种子。"
        >
          <LineChart :buckets="buckets" :series="orderSeries" unit="笔" label="订单量" />
        </ChartFrame>

        <ChartFrame
          title="风险告警与工单趋势"
          note="按创建时间统计范围内新建；告警不是欺诈事实。"
          :has-data="eventHasData"
          empty-text="当前范围没有告警、工单或复核推进记录。"
        >
          <LineChart :buckets="buckets" :series="eventSeries" unit="次" label="运营事件" />
        </ChartFrame>

        <ChartFrame
          title="当前风险级别快照"
          :note="`截至 ${utcLabel(data.snapshot.asOf)} 的当前状态，不受趋势时间范围过滤；不是历史时点状态。`"
          :has-data="alertsSnapshotHasData"
          empty-text="当前没有风险告警记录。"
        >
          <DonutChart :items="riskItems" label="当前风险级别" />
        </ChartFrame>

        <ChartFrame
          title="当前工单状态快照"
          :note="`截至 ${utcLabel(data.snapshot.asOf)} 的当前状态；“已解决”仅统计 status=RESOLVED。`"
          :has-data="workOrdersSnapshotHasData"
          empty-text="当前没有工单记录。"
        >
          <BarChart :items="statusItems" label="当前工单状态" unit="单" :rotate-labels="false" />
        </ChartFrame>

        <ChartFrame
          title="人工复核结论分布"
          note="按范围内 ads_alert_review.created_at；只是人工结论，不是准确率或客观真值。"
          :has-data="Boolean(data.reviews.hasData)"
          empty-text="当前范围没有复核记录。"
        >
          <BarChart :items="reviewItems" label="人工复核结论" unit="次" :rotate-labels="false" />
        </ChartFrame>

        <ChartFrame
          title="最近已结束导入批次"
          note="最近 20 个 SUCCEEDED/PARTIAL/FAILED 批次，按 started_at 倒序；FAILED 不会标成成功。"
          :has-data="Boolean(data.imports.hasData)"
          empty-text="当前范围没有已结束导入批次。"
        >
          <ImportBatchChart :batches="data.imports.batches" />
        </ChartFrame>
      </div>

      <article class="chart-card dq-card">
        <header class="chart-card__head">
          <div>
            <h2>数据质量摘要</h2>
            <p class="chart-card__note">
              DQ 通过只代表检查项通过，不代表模型有效；INFO 不进入通过率分子/分母。
              全局只展示检查项数量，来源质量请看下方按 sourceSystem 拆分。
            </p>
          </div>
          <span class="chart-card__unit">已结束批次</span>
        </header>
        <div v-if="dq.hasData" class="dq-grid">
          <div><span>ERROR/WARN 检查项</span><strong>{{ dq.evaluatedChecks }}</strong></div>
          <div><span>ERROR 失败</span><strong>{{ dq.failedError }}</strong></div>
          <div><span>WARN 失败</span><strong>{{ dq.failedWarn }}</strong></div>
          <div><span>INFO 检查项</span><strong>{{ dq.infoChecks }}（失败 {{ dq.infoFailed }}）</strong></div>
        </div>
        <div v-if="dq.hasData && dq.bySource?.length" class="table-wrap dq-source-table">
          <p class="chart-card__note">全局汇总范围：所有已结束批次；下表按 sourceSystem 分开，不能把 Olist 与 IEEE-CIS 的质量混为一个结论。</p>
          <table>
            <thead><tr><th>来源</th><th>通过率</th><th>ERROR/WARN</th><th>ERROR 失败</th><th>WARN 失败</th><th>INFO</th></tr></thead>
            <tbody>
              <tr v-for="source in dq.bySource" :key="source.sourceSystem">
                <td>{{ source.sourceSystem }}</td>
                <td>{{ formatPercent(source.passRate) }}</td>
                <td>{{ source.evaluatedChecks }}</td>
                <td>{{ source.failedError }}</td>
                <td>{{ source.failedWarn }}</td>
                <td>{{ source.infoChecks }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="chart-empty" role="status">
          <strong>暂无 DQ 检查项</strong>
          <span>没有 ERROR/WARN 检查项时通过率为空，不伪造 0%。</span>
        </div>
      </article>
    </template>
  </section>
</template>
