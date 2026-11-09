<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, resetCsrf } from './api.js'
import LoginPage from './pages/LoginPage.vue'
import OrdersPage from './pages/OrdersPage.vue'
import AlertsPage from './pages/AlertsPage.vue'
import AlertDetailPage from './pages/AlertDetailPage.vue'
import DataQualityPage from './pages/DataQualityPage.vue'
import ModelEvaluationPage from './pages/ModelEvaluationPage.vue'
import HealthPage from './pages/HealthPage.vue'

const user = ref(null)
const loading = ref(true)
const route = ref(parseHash())

const navItems = [
  { name: 'orders', label: '订单概览' },
  { name: 'alerts', label: '异常队列' },
  { name: 'quality', label: '数据质量' },
  { name: 'models', label: '模型评估' },
  { name: 'health', label: '系统健康' },
]

const roleLabels = {
  ANALYST: '分析员',
  REVIEWER: '复核员',
  ADMIN: '管理员',
}

const page = computed(() => {
  if (route.value.name === 'alerts' && route.value.id) {
    return { component: AlertDetailPage, props: { alertId: route.value.id } }
  }
  const pages = {
    orders: OrdersPage,
    alerts: AlertsPage,
    quality: DataQualityPage,
    models: ModelEvaluationPage,
    health: HealthPage,
  }
  return { component: pages[route.value.name] || OrdersPage, props: {} }
})

const roleText = computed(() => {
  if (!user.value?.roles?.length) {
    return ''
  }
  return user.value.roles.map((role) => roleLabels[role] || role).join(' / ')
})

function parseHash() {
  const raw = window.location.hash.replace(/^#\/?/, '')
  const parts = raw.split('/').filter(Boolean)
  return { name: parts[0] || 'orders', id: parts[1] || null }
}

function syncRoute() {
  route.value = parseHash()
}

function navigate(name, id) {
  window.location.hash = id ? `#/${name}/${id}` : `#/${name}`
  syncRoute()
}

async function checkSession() {
  try {
    user.value = await api('/api/v2/auth/me')
    if (!window.location.hash) {
      window.location.hash = '#/orders'
      syncRoute()
    }
  } catch {
    user.value = null
  } finally {
    loading.value = false
  }
}

async function handleLoggedIn() {
  resetCsrf()
  await checkSession()
  navigate('orders')
}

async function logout() {
  try {
    await api('/api/v2/auth/logout', { method: 'POST' })
  } catch {
    // 会话可能已过期；本地仍然清空。
  }
  resetCsrf()
  user.value = null
  window.location.hash = ''
  syncRoute()
}

function handleExpired() {
  user.value = null
  resetCsrf()
}

onMounted(() => {
  window.addEventListener('hashchange', syncRoute)
  window.addEventListener('auth-expired', handleExpired)
  checkSession()
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', syncRoute)
  window.removeEventListener('auth-expired', handleExpired)
})
</script>

<template>
  <div v-if="loading" class="app-loading">正在检查登录状态…</div>

  <LoginPage v-else-if="!user" @logged-in="handleLoggedIn" />

  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">FR</span>
        <div>
          <strong>订单风险复核台</strong>
          <small>虚构演示 · 非生产</small>
        </div>
      </div>
      <nav>
        <button
          v-for="item in navItems"
          :key="item.name"
          type="button"
          :class="['nav-item', { active: route.name === item.name }]"
          @click="navigate(item.name)"
        >
          {{ item.label }}
        </button>
      </nav>
      <div class="sidebar-note">
        <p>规则分不是欺诈概率；复核结论不是客观真值；Olist 与 IEEE-CIS 不互相补标。</p>
      </div>
    </aside>

    <div class="workspace">
      <header class="topbar">
        <div>
          <span class="muted">当前用户</span>
          <strong class="user-name">{{ user.username }}</strong>
          <span class="role-chip">{{ roleText }}</span>
        </div>
        <button type="button" class="ghost" @click="logout">退出登录</button>
      </header>

      <main class="content">
        <component
          :is="page.component"
          :key="route.name + (route.id || '')"
          v-bind="page.props"
          :user="user"
          @navigate="navigate"
        />
      </main>
    </div>
  </div>
</template>
