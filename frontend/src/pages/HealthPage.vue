<script setup>
import { onMounted, ref } from 'vue'
import { formatTime } from '../api.js'

defineProps({ user: { type: Object, default: null } })

const health = ref(null)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await fetch('/api/v1/health', { credentials: 'same-origin' })
    const body = await response.json()
    health.value = body
    if (!response.ok && !body?.status) {
      error.value = `健康检查失败（HTTP ${response.status}）`
    }
  } catch {
    error.value = '无法连接后端服务。'
    health.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <p class="eyebrow">运行状态</p>
        <h1>系统健康</h1>
        <p class="muted">健康端点匿名可访问；不返回主机、账号、连接串或异常堆栈。</p>
      </div>
      <button type="button" class="ghost" @click="load">刷新</button>
    </header>

    <p v-if="error" class="error-box" role="alert">{{ error }}</p>
    <div v-if="loading" class="state">正在检查…</div>

    <template v-else-if="health">
      <article class="card">
        <h2>
          总体状态
          <span class="status" :class="health.status === 'UP' ? 'paid' : 'cancelled'">{{ health.status }}</span>
        </h2>
        <dl class="facts">
          <dt>服务</dt><dd>{{ health.service }}</dd>
          <dt>存储模式</dt><dd>{{ health.storageMode }}</dd>
          <dt>数据库</dt>
          <dd>
            <span class="status" :class="health.checks?.database?.status === 'UP' ? 'paid' : 'cancelled'">
              {{ health.checks?.database?.status || 'UNKNOWN' }}
            </span>
            <small v-if="health.checks?.database?.detail"> · {{ health.checks.database.detail }}</small>
          </dd>
          <dt>模型</dt>
          <dd>
            <span class="status" :class="health.checks?.model?.status === 'UP' ? 'paid' : 'cancelled'">
              {{ health.checks?.model?.status || 'UNKNOWN' }}
            </span>
            <small v-if="health.checks?.model?.modelVersion">
              · {{ health.checks.model.modelType }} / {{ health.checks.model.modelVersion }}
            </small>
          </dd>
          <dt>检查时间</dt><dd>{{ formatTime(health.timestamp) }}</dd>
        </dl>
        <p v-if="health.storageMode === 'stub'" class="warning-box">
          当前为内存契约演示（database=BYPASS），不代表 MySQL 连接成功。503 是预期降级。
        </p>
        <p v-else-if="health.status !== 'UP'" class="warning-box">
          存在依赖异常；请检查数据库或模型服务，不要把降级误报为正常。
        </p>
      </article>
    </template>
  </section>
</template>
