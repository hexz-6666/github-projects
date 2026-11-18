<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { AlertTriangle, Pause, Play, RefreshCw, ScrollText, Trash2, X } from '@lucide/vue'
import { api, formatTime } from '../api.js'

const props = defineProps({
  user: { type: Object, default: null },
})

const open = ref(false)
const paused = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const items = ref([])
const nextCursor = ref(null)
const hasMore = ref(false)
const health = ref(null)
const trigger = ref(null)
const closeButton = ref(null)
let timer = null
let requestSequence = 0

const filters = ref({
  source: '',
  minLevel: 'WARN',
  taskType: '',
  relatedRequestId: '',
})

const isAdmin = computed(() => (props.user?.roles || []).includes('ADMIN'))
const healthText = computed(() => {
  const status = health.value?.status
  if (status === 'DEGRADED') return '日志功能降级，不影响业务操作'
  if (status === 'DISABLED') return '日志功能已关闭'
  return ''
})

function queryString() {
  const params = new URLSearchParams({ minLevel: filters.value.minLevel, limit: '50' })
  if (filters.value.source) params.set('source', filters.value.source)
  if (filters.value.taskType.trim()) params.set('taskType', filters.value.taskType.trim())
  if (filters.value.relatedRequestId.trim()) {
    params.set('relatedRequestId', filters.value.relatedRequestId.trim())
  }
  return params.toString()
}

async function loadHealth() {
  try {
    health.value = await api('/api/v3/ops/health')
  } catch (requestError) {
    health.value = { status: 'DEGRADED' }
    if (!error.value) error.value = requestError.message
  }
}

async function refresh() {
  if (!open.value || paused.value || loading.value) return
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  try {
    const body = await api(`/api/v3/ops/logs?${queryString()}`)
    if (sequence !== requestSequence) return
    items.value = body.items || []
    nextCursor.value = body.nextCursor || null
    hasMore.value = Boolean(body.hasMore)
  } catch (requestError) {
    if (sequence !== requestSequence) return
    error.value = requestError.status === 403
      ? '当前账号没有查看运行日志的权限。'
      : requestError.message
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || !nextCursor.value || loadingMore.value) return
  loadingMore.value = true
  error.value = ''
  try {
    const params = new URLSearchParams(queryString())
    params.set('cursor', nextCursor.value)
    const body = await api(`/api/v3/ops/logs?${params.toString()}`)
    items.value = items.value.concat(body.items || [])
    nextCursor.value = body.nextCursor || null
    hasMore.value = Boolean(body.hasMore)
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loadingMore.value = false
  }
}

async function applyFilters() {
  requestSequence += 1
  await refresh()
}

function clearView() {
  items.value = []
  nextCursor.value = null
  hasMore.value = false
}

function stopPolling() {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
}

function startPolling() {
  stopPolling()
  if (open.value && !paused.value) {
    timer = setInterval(refresh, 5000)
  }
}

async function openWindow() {
  open.value = true
  paused.value = false
  await nextTick()
  closeButton.value?.focus()
  await loadHealth()
  await refresh()
  startPolling()
}

async function closeWindow() {
  open.value = false
  stopPolling()
  await nextTick()
  trigger.value?.focus()
}

function togglePause() {
  paused.value = !paused.value
  startPolling()
}

function handleKeydown(event) {
  if (event.key === 'Escape' && open.value) {
    closeWindow()
  }
}

watch(open, (value) => {
  if (!value) stopPolling()
})
watch(paused, () => startPolling())

onBeforeUnmount(stopPolling)
</script>

<template>
  <div v-if="isAdmin" class="ops-log-root" @keydown="handleKeydown">
    <button
      ref="trigger"
      type="button"
      class="ops-log-trigger"
      aria-label="打开运行日志"
      :aria-expanded="open ? 'true' : 'false'"
      @click="open ? closeWindow() : openWindow()"
    >
      <ScrollText :size="17" aria-hidden="true" />
      <span>运行日志</span>
      <span v-if="items.length" class="ops-log-count">{{ items.length }}</span>
    </button>

    <section
      v-if="open"
      class="ops-log-panel"
      role="dialog"
      aria-modal="false"
      aria-label="运行日志"
    >
      <header class="ops-log-head">
        <div>
          <p class="eyebrow">诊断信息</p>
          <h2>运行日志</h2>
        </div>
        <button ref="closeButton" type="button" class="icon-button" aria-label="关闭运行日志" @click="closeWindow">
          <X :size="18" />
        </button>
      </header>

      <p v-if="healthText" class="warning-box" role="status">{{ healthText }}</p>
      <p v-if="error" class="error-box" role="alert">{{ error }}</p>

      <div class="ops-log-filters">
        <label>
          <span>来源</span>
          <select v-model="filters.source" @change="applyFilters">
            <option value="">全部</option>
            <option value="FRONTEND">FRONTEND</option>
            <option value="BACKEND">BACKEND</option>
            <option value="MODEL">MODEL</option>
            <option value="TASK">TASK</option>
          </select>
        </label>
        <label>
          <span>最低级别</span>
          <select v-model="filters.minLevel" @change="applyFilters">
            <option value="WARN">WARN</option>
            <option value="INFO">INFO</option>
            <option value="ERROR">ERROR</option>
          </select>
        </label>
        <label>
          <span>任务类型</span>
          <input v-model="filters.taskType" placeholder="如 WORK_ORDER" @keyup.enter="applyFilters" />
        </label>
        <label>
          <span>关联 requestId</span>
          <input v-model="filters.relatedRequestId" placeholder="失败请求的 requestId" @keyup.enter="applyFilters" />
        </label>
      </div>

      <div class="ops-log-toolbar">
        <button type="button" class="ghost" :disabled="loading" @click="applyFilters">
          <RefreshCw :size="15" /> 刷新
        </button>
        <button type="button" class="ghost" @click="togglePause">
          <Play v-if="paused" :size="15" /><Pause v-else :size="15" />
          {{ paused ? '继续' : '暂停' }}
        </button>
        <button type="button" class="ghost" @click="clearView">
          <Trash2 :size="15" /> 清屏
        </button>
      </div>

      <div v-if="loading && !items.length" class="state">正在加载运行日志…</div>
      <div v-else-if="!items.length" class="ops-log-empty">
        <AlertTriangle :size="18" />
        <p>当前筛选条件下没有日志。</p>
      </div>
      <ol v-else class="ops-log-list" aria-live="polite">
        <li v-for="item in items" :key="item.eventId" class="ops-log-item">
          <div class="ops-log-item-head">
            <span class="status" :class="item.level === 'ERROR' ? 'cancelled' : item.level === 'WARN' ? 'warning' : 'paid'">
              {{ item.level }}
            </span>
            <strong>{{ item.code }}</strong>
            <time>{{ formatTime(item.timestamp) }}</time>
          </div>
          <p>{{ item.summary }}</p>
          <dl>
            <dt>来源</dt><dd>{{ item.source }}</dd>
            <dt v-if="item.taskType">任务</dt><dd v-if="item.taskType">{{ item.taskType }}</dd>
            <dt v-if="item.route">路由</dt><dd v-if="item.route">{{ item.route }}</dd>
            <dt v-if="item.relatedRequestId">关联</dt>
            <dd v-if="item.relatedRequestId" class="ops-log-id">{{ item.relatedRequestId }}</dd>
            <dt v-if="item.requestId">requestId</dt>
            <dd v-if="item.requestId" class="ops-log-id">{{ item.requestId }}</dd>
          </dl>
        </li>
      </ol>

      <button v-if="hasMore" type="button" class="ghost ops-log-more" :disabled="loadingMore" @click="loadMore">
        {{ loadingMore ? '正在加载…' : '加载更早' }}
      </button>
    </section>
  </div>
</template>
