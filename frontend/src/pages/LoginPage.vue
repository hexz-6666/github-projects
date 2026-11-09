<script setup>
import { ref } from 'vue'
import { api } from '../api.js'

const emit = defineEmits(['logged-in'])
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

const accounts = {
  analyst: ['demo_analyst', 'Analyst-Demo-2026!'],
  reviewer: ['demo_reviewer', 'Reviewer-Demo-2026!'],
  admin: ['demo_admin', 'Admin-Demo-2026!'],
}

function fill(kind) {
  const selected = accounts[kind]
  username.value = selected[0]
  password.value = selected[1]
  error.value = ''
}

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await api('/api/v2/auth/login', {
      method: 'POST',
      body: { username: username.value, password: password.value },
    })
    emit('logged-in')
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-wrap">
    <section class="login-card">
      <p class="eyebrow">本地虚构演示</p>
      <h1>订单风险复核台</h1>
      <p class="muted">登录后才能查看订单、告警与数据质量。所有账号与数据均为虚构，不可用于生产。</p>
      <form class="stack" @submit.prevent="submit">
        <label>
          <span>用户名</span>
          <input v-model.trim="username" autocomplete="username" maxlength="64" required />
        </label>
        <label>
          <span>密码</span>
          <input v-model="password" type="password" autocomplete="current-password" maxlength="128" required />
        </label>
        <p v-if="error" class="error-box" role="alert">{{ error }}</p>
        <button class="primary" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登录' }}
        </button>
      </form>
      <div class="demo-accounts">
        <span class="muted">演示账号：</span>
        <button type="button" class="link" @click="fill('analyst')">分析员</button>
        <button type="button" class="link" @click="fill('reviewer')">复核员</button>
        <button type="button" class="link" @click="fill('admin')">管理员</button>
      </div>
      <p class="fine-print">默认密码只用于本机演示，部署时应通过环境变量替换并强制修改。</p>
    </section>
  </main>
</template>
