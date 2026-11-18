<script setup>
import { onMounted, ref } from 'vue'
import { api, formatTime } from '../api.js'

defineProps({ user: { type: Object, default: null } })
const emit = defineEmits(['navigate'])

const orders = ref([])
const pageInfo = ref({ number: 0, size: 20, totalElements: 0, totalPages: 0 })
const loading = ref(false)
const error = ref('')
const status = ref('')
const keyword = ref('')
const page = ref(0)

async function load() {
  loading.value = true
  error.value = ''
  const params = new URLSearchParams({ page: String(page.value), size: '20' })
  if (status.value) params.set('status', status.value)
  if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
  try {
    const body = await api(`/api/v1/orders?${params.toString()}`)
    orders.value = body.items
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

function resetFilters() {
  status.value = ''
  keyword.value = ''
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
        <p class="eyebrow">订单数仓 · DWD</p>
        <h1>订单概览</h1>
        <p class="muted">金额以字符串返回，避免浏览器浮点误差。订单状态是业务履约状态，不是欺诈标签。</p>
      </div>
      <button type="button" class="ghost" @click="load">刷新</button>
    </header>

    <form class="filters" @submit.prevent="applyFilters">
      <label>
        <span>状态</span>
        <select v-model="status">
          <option value="">全部</option>
          <option value="CREATED">CREATED</option>
          <option value="PAID">PAID</option>
          <option value="CANCELLED">CANCELLED</option>
        </select>
      </label>
      <label class="grow">
        <span>关键字</span>
        <input v-model="keyword" maxlength="64" placeholder="订单 ID / 订单号 / 客户引用" />
      </label>
      <button type="submit" class="primary">筛选</button>
      <button type="button" class="ghost" @click="resetFilters">重置</button>
    </form>

    <p v-if="error" class="error-box" role="alert">{{ error }}</p>
    <div v-if="loading" class="state">正在加载订单…</div>
    <div v-else-if="!orders.length" class="state">没有符合条件的订单。</div>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>订单</th>
            <th>客户引用</th>
            <th>邮箱（掩码）</th>
            <th>状态</th>
            <th>金额</th>
            <th>来源</th>
            <th>下单时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="order in orders" :key="order.orderId">
            <td>
              <strong>{{ order.orderId }}</strong>
              <small>{{ order.orderNo }}</small>
            </td>
            <td>{{ order.customerRef }}</td>
            <td>{{ order.customerEmailMasked || '—' }}</td>
            <td><span class="status" :class="order.status.toLowerCase()">{{ order.status }}</span></td>
            <td class="amount">{{ order.amount }} {{ order.currency }}</td>
            <td>{{ order.dataOrigin }}</td>
            <td>{{ formatTime(order.orderedAt) }}</td>
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
    <p class="fine-print">页面不展示 ODS 原始载荷；掩码不是加密。无欺诈真值的订单不会显示为“已确认欺诈”。</p>
  </section>
</template>
