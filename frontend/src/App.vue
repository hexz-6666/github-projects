<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Activity,
  AlertTriangle,
  BrainCircuit,
  ChevronRight,
  Database,
  LogOut,
  Menu,
  PanelLeftClose,
  PanelLeftOpen,
  ReceiptText,
  ShieldCheck,
  X,
} from '@lucide/vue'
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
const collapsed = ref(false)
const mobileOpen = ref(false)
const menuButton = ref(null)
const closeButton = ref(null)
const mobileQuery = window.matchMedia('(max-width: 900px)')
const isMobile = ref(mobileQuery.matches)

const navGroups = [
  {
    label: '风险业务',
    items: [
      { name: 'orders', label: '订单概览', icon: ReceiptText },
      { name: 'alerts', label: '异常队列', icon: AlertTriangle },
    ],
  },
  {
    label: '数据与模型',
    items: [
      { name: 'quality', label: '数据质量', icon: Database },
      { name: 'models', label: '模型评估', icon: BrainCircuit },
    ],
  },
  {
    label: '系统',
    items: [{ name: 'health', label: '系统健康', icon: Activity }],
  },
]

const roleLabels = {
  ANALYST: '分析员',
  REVIEWER: '复核员',
  ADMIN: '管理员',
}

const pages = {
  orders: OrdersPage,
  alerts: AlertsPage,
  quality: DataQualityPage,
  models: ModelEvaluationPage,
  health: HealthPage,
}

const page = computed(() => {
  if (route.value.name === 'alerts' && route.value.id) {
    return { component: AlertDetailPage, props: { alertId: route.value.id } }
  }
  return { component: pages[route.value.name] || OrdersPage, props: {} }
})

const currentItem = computed(() => {
  if (route.value.name === 'alerts' && route.value.id) {
    return { label: '告警详情', group: '风险业务' }
  }
  for (const group of navGroups) {
    const item = group.items.find((candidate) => candidate.name === route.value.name)
    if (item) {
      return { ...item, group: group.label }
    }
  }
  return { label: '订单概览', group: '风险业务' }
})

const roleText = computed(() => {
  if (!user.value?.roles?.length) {
    return ''
  }
  return user.value.roles.map((role) => roleLabels[role] || role).join(' / ')
})

const initials = computed(() => (user.value?.username || '?').slice(0, 1).toUpperCase())

function parseHash() {
  const raw = window.location.hash.replace(/^#\/?/, '')
  const parts = raw.split('/').filter(Boolean)
  return { name: parts[0] || 'orders', id: parts[1] || null }
}

function syncRoute() {
  route.value = parseHash()
  if (mobileOpen.value) {
    closeMobileNav()
  }
}

function navigate(name, id) {
  window.location.hash = id ? `#/${name}/${id}` : `#/${name}`
  syncRoute()
}

async function openMobileNav() {
  mobileOpen.value = true
  await nextTick()
  closeButton.value?.focus()
}

async function closeMobileNav() {
  const wasOpen = mobileOpen.value
  mobileOpen.value = false
  if (wasOpen) {
    await nextTick()
    menuButton.value?.focus()
  }
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
  mobileOpen.value = false
  window.location.hash = ''
  syncRoute()
}

function handleExpired() {
  user.value = null
  resetCsrf()
  mobileOpen.value = false
}

function handleKeydown(event) {
  if (event.key === 'Escape' && mobileOpen.value) {
    closeMobileNav()
  }
}

function handleViewportChange(event) {
  isMobile.value = event.matches
  if (!event.matches) {
    mobileOpen.value = false
  }
}

onMounted(() => {
  window.addEventListener('hashchange', syncRoute)
  window.addEventListener('auth-expired', handleExpired)
  window.addEventListener('keydown', handleKeydown)
  mobileQuery.addEventListener('change', handleViewportChange)
  checkSession()
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', syncRoute)
  window.removeEventListener('auth-expired', handleExpired)
  window.removeEventListener('keydown', handleKeydown)
  mobileQuery.removeEventListener('change', handleViewportChange)
})
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>

  <div v-if="loading" class="app-loading">正在检查登录状态…</div>

  <LoginPage v-else-if="!user" @logged-in="handleLoggedIn" />

  <div v-else :class="['shell', { 'is-collapsed': collapsed }]">
    <button
      v-if="mobileOpen"
      type="button"
      class="sidebar-backdrop"
      aria-label="关闭导航"
      @click="closeMobileNav"
    ></button>

    <aside
      id="app-sidebar"
      :class="['sidebar', { 'is-open': mobileOpen }]"
      :aria-hidden="isMobile && !mobileOpen ? 'true' : undefined"
      :inert="isMobile && !mobileOpen ? true : undefined"
      aria-label="主导航"
    >
      <div class="sidebar-brand">
        <span class="brand-mark" aria-hidden="true"><ShieldCheck :size="20" /></span>
        <div class="brand-copy">
          <strong>订单风险复核台</strong>
          <small>虚构演示 · 非生产</small>
        </div>
        <button
          type="button"
          class="icon-button sidebar-collapse"
          :aria-label="collapsed ? '展开侧栏' : '折叠侧栏'"
          :aria-expanded="!collapsed"
          @click="collapsed = !collapsed"
        >
          <PanelLeftOpen v-if="collapsed" :size="18" />
          <PanelLeftClose v-else :size="18" />
        </button>
        <button
          ref="closeButton"
          type="button"
          class="icon-button sidebar-close-mobile"
          aria-label="关闭导航"
          @click="closeMobileNav"
        >
          <X :size="19" />
        </button>
      </div>

      <nav class="side-nav">
        <div v-for="group in navGroups" :key="group.label" class="nav-group">
          <p class="nav-group-label">{{ group.label }}</p>
          <button
            v-for="item in group.items"
            :key="item.name"
            type="button"
            :class="['nav-item', { active: route.name === item.name }]"
            :aria-label="item.label"
            :aria-current="route.name === item.name ? 'page' : undefined"
            :title="collapsed ? item.label : undefined"
            @click="navigate(item.name)"
          >
            <component :is="item.icon" :size="18" class="nav-icon" aria-hidden="true" />
            <span class="nav-label">{{ item.label }}</span>
            <ChevronRight v-if="route.name === item.name" :size="15" class="nav-active-mark" aria-hidden="true" />
          </button>
        </div>
      </nav>

      <div class="sidebar-foot">
        <p>规则分不是欺诈概率；告警不是欺诈事实；复核结论不是客观真值。</p>
        <span class="sidebar-env">LOCAL DEMO</span>
      </div>
    </aside>

    <div class="workspace">
      <header class="topbar">
        <div class="topbar-left">
          <button
            ref="menuButton"
            type="button"
            class="icon-button mobile-menu"
            aria-label="打开导航"
            aria-controls="app-sidebar"
            :aria-expanded="mobileOpen ? 'true' : 'false'"
            @click="openMobileNav"
          >
            <Menu :size="20" />
          </button>
          <div class="topbar-context">
            <span>{{ currentItem.group }}</span>
            <strong>{{ currentItem.label }}</strong>
          </div>
        </div>

        <div class="topbar-user">
          <span class="user-avatar" aria-hidden="true">{{ initials }}</span>
          <div class="user-meta">
            <strong>{{ user.username }}</strong>
            <small>{{ roleText }}</small>
          </div>
          <button type="button" class="ghost logout-button" aria-label="退出登录" @click="logout">
            <LogOut :size="16" aria-hidden="true" />
            <span>退出登录</span>
          </button>
        </div>
      </header>

      <main id="main-content" class="content" tabindex="-1">
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
