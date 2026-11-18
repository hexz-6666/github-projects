<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api, formatTime } from '../api.js'

const props = defineProps({
  user: { type: Object, default: null },
})
const emit = defineEmits(['navigate'])

const items = ref([])
const loading = ref(true)
const error = ref('')
const scope = ref('mine')
const status = ref('')
const page = ref(0)
const pageInfo = ref({ totalElements: 0, totalPages: 0 })
const size = 20

const roles = computed(() => props.user?.roles || [])
const isAdmin = computed(() => roles.value.includes('ADMIN'))
const scopes = computed(() => {
  const base = [
    { value: 'mine', label: '我的工单' },
    { value: 'queue', label: '待领取队列' },
  ]
  if (isAdmin.value) base.push({ value: 'all', label: '全部工单' })
  return base
})

const slaLabels = {
  ON_TRACK: '按期',
  OVERDUE: '已逾期',
  MET: '按期完成',
  BREACHED: '逾期完成',
  CANCELLED: '已取消',
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({ scope: scope.value, page: String(page.value), size: String(size) })
    if (status.value) params.set('status', status.value)
    const result = await api(`/api/v3/work-orders?${params}`)
    items.value = result.items || []
    pageInfo.value = result.page || { totalElements: 0, totalPages: 0 }
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}

function changeScope(value) {
  scope.value = value
  page.value = 0
  load()
}

function changePage(delta) {
  const next = page.value + delta
  if (next < 0 || (pageInfo.value.totalPages && next >= pageInfo.value.totalPages)) return
  page.value = next
  load()
}

function openOrder(item) {
  emit('navigate', 'workorders', item.id)
}

watch(status, () => {
  page.value = 0
  load()
})

onMounted(load)
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1>工单队列</h1>
        <p class="muted">风险告警的处置工作台；工单只由复核员或管理员从匹配配置显式创建。</p>
      </div>
      <button type="button" class="ghost" :disabled="loading" @click="load">刷新</button>
    </header>

    <p v-if="error" class="error-box" role="alert">{{ error }}</p>

    <article class="card">
      <div class="tabs" role="tablist" aria-label="工单范围">
        <button
          v-for="item in scopes"
          :key="item.value"
          type="button"
          class="tab"
          :class="{ active: scope === item.value }"
          role="tab"
          :aria-selected="scope === item.value"
          @click="changeScope(item.value)"
        >
          {{ item.label }}
        </button>
      </div>

      <div class="filters">
        <label>
          <span>状态</span>
          <select v-model="status">
            <option value="">全部状态</option>
            <option value="OPEN">OPEN</option>
            <option value="ASSIGNED">ASSIGNED</option>
            <option value="IN_PROGRESS">IN_PROGRESS</option>
            <option value="RESOLVED">RESOLVED</option>
            <option value="CANCELLED">CANCELLED</option>
          </select>
        </label>
        <span class="muted small">共 {{ pageInfo.totalElements }} 条</span>
      </div>

      <div v-if="loading" class="state">正在加载工单…</div>
      <div v-else-if="!items.length" class="state">当前范围没有工单。</div>
      <div v-else class="table-wrap">
        <table class="data-table wo-table">
          <thead>
            <tr>
              <th>工单</th><th>告警</th><th>配置</th><th>状态</th><th>SLA</th><th>处理人</th><th>截止时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in items" :key="item.id">
              <td><button type="button" class="link" @click="openOrder(item)">#{{ item.id }}</button></td>
              <td>#{{ item.alertId }}</td>
              <td>{{ item.configCode }} <small>v{{ item.configVersionNo }}</small></td>
              <td><span class="status" :class="item.status.toLowerCase()">{{ item.status }}</span></td>
              <td><span class="sla" :class="item.slaStatus.toLowerCase()">{{ slaLabels[item.slaStatus] || item.slaStatus }}</span></td>
              <td>{{ item.assigneeId || '—' }}</td>
              <td>{{ formatTime(item.dueAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pager">
        <button type="button" class="ghost" :disabled="page === 0" @click="changePage(-1)">上一页</button>
        <span class="muted">第 {{ page + 1 }} 页 / 共 {{ pageInfo.totalPages || 0 }} 页</span>
        <button type="button" class="ghost"
          :disabled="!pageInfo.totalPages || page + 1 >= pageInfo.totalPages" @click="changePage(1)">下一页</button>
      </div>
    </article>
  </section>
</template>
