<script setup>
import { onMounted, ref } from 'vue'
import { api, formatTime } from '../api.js'

defineProps({ user: { type: Object, default: null } })
const emit = defineEmits(['navigate'])

const alerts = ref([])
const pageInfo = ref({ number: 0, size: 20, totalElements: 0, totalPages: 0 })
const loading = ref(false)
const error = ref('')
const alertStatus = ref('')
const riskLevel = ref('')
const page = ref(0)

async function load() {
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({ page: String(page.value), size: '20' })
  if (alertStatus.value) params.set('alertStatus', alertStatus.value)
  if (riskLevel.value) params.set('riskLevel', riskLevel.value)
  try {
    const body = await api(`/api/v1/alerts?${params.toString()}`)
    alerts.value = body.items
    pageInfo.value = body.page
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  page.value = 0
  load()
}

function go(delta) {
  const next = pageInfo.value.number + delta
  if (next < 0 || next >= pageInfo.value.totalPages) return
  page.value = next
  load()
}

onMounted(load)
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <p class="eyebrow">ADS · 异常队列</p>
        <h1>异常队列</h1>
        <p class="muted">告警是待检查的风险信号，不是欺诈事实。规则分不是真实欺诈概率。</p>
      </div>
      <button type="button" class="ghost" @click="load">刷新</button>
    </header>

    <form class="filters" @submit.prevent="applyFilters">
      <label>
        <span>处置状态</span>
        <select v-model="alertStatus">
          <option value="">全部</option>
          <option value="OPEN">OPEN</option>
          <option value="REVIEWING">REVIEWING</option>
          <option value="RESOLVED">RESOLVED</option>
        </select>
      </label>
      <label>
        <span>风险等级</span>
        <select v-model="riskLevel">
          <option value="">全部</option>
          <option value="LOW">LOW</option>
          <option value="MEDIUM">MEDIUM</option>
          <option value="HIGH">HIGH</option>
        </select>
      </label>
      <button type="submit" class="primary">筛选</button>
    </form>

    <p v-if="error" class="error-box" role="alert">{{ error }}</p>
    <div v-if="loading" class="state">正在加载告警…</div>
    <div v-else-if="!alerts.length" class="state">当前筛选条件下没有告警。</div>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>告警</th>
            <th>订单</th>
            <th>状态</th>
            <th>风险</th>
            <th>分数</th>
            <th>原因码</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="alert in alerts" :key="alert.alertId">
            <td>
              <button type="button" class="link" @click="emit('navigate', 'alerts', alert.alertId)">
                #{{ alert.alertId }}
              </button>
            </td>
            <td>{{ alert.orderId }}</td>
            <td><span class="status" :class="alert.alertStatus.toLowerCase()">{{ alert.alertStatus }}</span></td>
            <td><span class="risk" :class="alert.riskLevel.toLowerCase()">{{ alert.riskLevel }}</span></td>
            <td class="amount">{{ Number(alert.score).toFixed(4) }}</td>
            <td>
              <span v-for="code in alert.reasonCodes" :key="code" class="code">{{ code }}</span>
              <span v-if="!alert.reasonCodes?.length" class="muted">无</span>
            </td>
            <td>{{ formatTime(alert.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <footer class="pager">
      <span>共 {{ pageInfo.totalElements }} 条 · 第 {{ pageInfo.number + 1 }} / {{ Math.max(pageInfo.totalPages, 1) }} 页</span>
      <div>
        <button type="button" class="ghost" :disabled="pageInfo.number <= 0" @click="go(-1)">上一页</button>
        <button
          type="button"
          class="ghost"
          :disabled="pageInfo.number + 1 >= pageInfo.totalPages"
          @click="go(1)"
        >下一页</button>
      </div>
    </footer>
  </section>
</template>
