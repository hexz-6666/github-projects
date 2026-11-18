<script setup>
import { nextTick, ref } from 'vue'
import { AlertTriangle, LockKeyhole, ShieldCheck, UserRound } from '@lucide/vue'
import loginVisual from '../assets/login-risk-visual.webp'
import { api } from '../api.js'

const emit = defineEmits(['logged-in'])

const REMEMBER_KEY = 'fraud_demo.remembered_username'
const remembered = readRememberedUsername()
const username = ref(remembered)
const password = ref('')
const remember = ref(Boolean(remembered))
const error = ref('')
const loading = ref(false)
const passwordInput = ref(null)

const demoAccounts = [
  { label: '分析员', username: 'demo_analyst' },
  { label: '复核员', username: 'demo_reviewer' },
  { label: '管理员', username: 'demo_admin' },
]

function readRememberedUsername() {
  try {
    return (localStorage.getItem(REMEMBER_KEY) || '').trim()
  } catch {
    return ''
  }
}

function saveRememberedUsername(value) {
  try {
    const trimmed = value.trim()
    if (remember.value && trimmed) {
      localStorage.setItem(REMEMBER_KEY, trimmed)
    } else {
      localStorage.removeItem(REMEMBER_KEY)
    }
  } catch {
    // 浏览器禁用存储时，不影响登录本身。
  }
}

function handleRememberChange() {
  if (!remember.value) {
    try {
      localStorage.removeItem(REMEMBER_KEY)
    } catch {
      // 忽略存储不可用。
    }
  }
}

function fillDemo(account) {
  username.value = account.username
  password.value = ''
  error.value = ''
  nextTick(() => passwordInput.value?.focus())
}

async function submit() {
  error.value = ''
  loading.value = true
  const loginUsername = username.value.trim()
  try {
    await api('/api/v2/auth/login', {
      method: 'POST',
      body: { username: loginUsername, password: password.value },
    })
    saveRememberedUsername(loginUsername)
    emit('logged-in')
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-visual" aria-label="订单风险与数据分析主题视觉">
      <img class="login-visual__image" :src="loginVisual" alt="" />
      <div class="login-visual__scrim"></div>
      <div class="login-visual__brand">
        <span class="brand-mark" aria-hidden="true"><ShieldCheck :size="20" /></span>
        <div>
          <strong>订单风险复核台</strong>
          <small>LOCAL SYNTHETIC DEMO</small>
        </div>
      </div>
      <div class="login-visual__copy">
        <p class="eyebrow">ORDER RISK · REVIEW WORKBENCH</p>
        <h2>从订单信号到人工判断，<br />每一步都保留边界。</h2>
        <p>风险分不是欺诈概率。告警需要复核，结论不自动成为真值。</p>
        <div class="login-visual__pills" aria-label="功能边界">
          <span>订单数仓</span>
          <span>异常检测</span>
          <span>人工复核</span>
        </div>
      </div>
      <p class="login-visual__foot">虚构数据 · 非生产环境 · 不包含真实个人信息</p>
    </section>

    <section class="login-panel">
      <div class="login-panel__inner">
        <div class="mobile-brand">
          <span class="brand-mark" aria-hidden="true"><ShieldCheck :size="19" /></span>
          <strong>订单风险复核台</strong>
        </div>

        <p class="eyebrow">本地虚构演示</p>
        <h1>登录复核台</h1>
        <p class="login-lede">使用演示账号查看订单、风险告警与数据质量。所有账号和数据均为虚构。</p>

        <form class="login-form" @submit.prevent="submit">
          <label class="field" for="login-username">
            <span>用户名</span>
            <span class="input-shell">
              <UserRound :size="17" aria-hidden="true" />
              <input
                id="login-username"
                v-model.trim="username"
                name="username"
                autocomplete="username"
                autocapitalize="none"
                spellcheck="false"
                maxlength="64"
                required
                autofocus
              />
            </span>
          </label>

          <label class="field" for="login-password">
            <span>密码</span>
            <span class="input-shell">
              <LockKeyhole :size="17" aria-hidden="true" />
              <input
                id="login-password"
                ref="passwordInput"
                v-model="password"
                name="password"
                type="password"
                autocomplete="current-password"
                maxlength="128"
                required
              />
            </span>
          </label>

          <div class="remember-row">
            <label class="checkbox">
              <input
                v-model="remember"
                type="checkbox"
                name="remember-account"
                @change="handleRememberChange"
              />
              <span>记住账号</span>
            </label>
            <span class="remember-hint">只保存用户名，不保存密码</span>
          </div>

          <p class="password-hint">
            <AlertTriangle :size="14" aria-hidden="true" />
            密码可交给浏览器原生密码管理器；本应用不会把密码写入任何存储。
          </p>

          <p v-if="error" class="error-box" role="alert">{{ error }}</p>

          <button class="primary login-submit" type="submit" :disabled="loading">
            {{ loading ? '登录中…' : '登录' }}
          </button>
        </form>

        <details class="demo-accounts">
          <summary>本地演示账号（仅填入用户名）</summary>
          <div class="demo-account-list">
            <button
              v-for="account in demoAccounts"
              :key="account.username"
              type="button"
              class="link"
              @click="fillDemo(account)"
            >
              {{ account.label }} · {{ account.username }}
            </button>
          </div>
          <p class="fine-print">密码见项目 README 的虚构演示账号表；部署时应通过环境变量覆盖默认值。</p>
        </details>

        <p class="fine-print login-fine-print">
          本应用不是生产认证系统。会话、角色和 CSRF 仅用于本地演示；默认密码不得用于任何真实系统。
        </p>
      </div>
    </section>
  </main>
</template>
