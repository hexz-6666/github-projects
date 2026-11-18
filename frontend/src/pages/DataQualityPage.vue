<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, formatTime } from '../api.js'

const props = defineProps({ user: { type: Object, default: null } })

const summary = ref(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const importing = ref('')

const canImport = computed(() => (props.user?.roles || []).includes('ADMIN'))

async function load() {
  loading.value = true
  error.value = ''
  try {
    summary.value = await api('/api/v2/data-quality/summary')
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}

async function runImport(source) {
  importing.value = source
  error.value = ''
  notice.value = ''
  try {
    const result = await api('/api/v2/imports', {
      method: 'POST',
      body: { sourceSystem: source },
    })
    notice.value = result.idempotent
      ? `相同文件已导入过，复用批次 ${result.batchId}。`
      : `导入完成：${result.status}，接收 ${result.acceptedRows} 行，拒绝 ${result.rejectedRows} 行。`
    await load()
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    importing.value = ''
  }
}

onMounted(load)
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <p class="eyebrow">ETL · 数据质量</p>
        <h1>数据质量</h1>
        <p class="muted">Olist 与 IEEE-CIS 分源导入、分批登记；检查失败不会把外部标签写入订单。</p>
      </div>
      <button type="button" class="ghost" @click="load">刷新</button>
    </header>

    <p v-if="error" class="error-box" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-box" role="status">{{ notice }}</p>

    <article class="card">
      <h2>本地导入</h2>
      <p class="muted">
        将文件放到 <code>data/external/olist</code> 或 <code>data/external/ieee-cis</code>。
        导入按文件哈希幂等；重复请求会复用同一批次。
      </p>
      <div class="actions">
        <button type="button" class="primary" :disabled="!canImport || !!importing" @click="runImport('OLIST')">
          {{ importing === 'OLIST' ? '导入中…' : '导入 Olist' }}
        </button>
        <button type="button" class="primary" :disabled="!canImport || !!importing" @click="runImport('IEEE_CIS')">
          {{ importing === 'IEEE_CIS' ? '导入中…' : '导入 IEEE-CIS' }}
        </button>
      </div>
      <p v-if="!canImport" class="fine-print">只有管理员可以触发导入。</p>
    </article>

    <div v-if="loading" class="state">正在加载质量摘要…</div>
    <template v-else-if="summary?.batch">
      <article class="card">
        <h2>最近批次</h2>
        <dl class="facts">
          <dt>批次</dt><dd>{{ summary.batch.batchId }}</dd>
          <dt>来源</dt><dd>{{ summary.batch.sourceSystem }} / {{ summary.batch.datasetName }}</dd>
          <dt>状态</dt><dd><span class="status" :class="summary.batch.status.toLowerCase()">{{ summary.batch.status }}</span></dd>
          <dt>行数</dt><dd>总 {{ summary.batch.totalRows }} · 接收 {{ summary.batch.acceptedRows }} · 拒绝 {{ summary.batch.rejectedRows }}</dd>
          <dt>文件数</dt><dd>{{ summary.batch.fileCount }}</dd>
          <dt>操作者</dt><dd>{{ summary.batch.operatorId }}</dd>
          <dt>开始</dt><dd>{{ formatTime(summary.batch.startedAt) }}</dd>
          <dt>结束</dt><dd>{{ formatTime(summary.batch.finishedAt) }}</dd>
          <dt v-if="summary.batch.errorSummary">摘要</dt>
          <dd v-if="summary.batch.errorSummary">{{ summary.batch.errorSummary }}</dd>
        </dl>
      </article>

      <article class="card">
        <h2>检查结果</h2>
        <div v-if="!summary.checks?.length" class="muted">没有检查记录。</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr><th>检查</th><th>级别</th><th>结果</th><th>观测</th><th>期望</th><th>说明</th></tr>
            </thead>
            <tbody>
              <tr v-for="check in summary.checks" :key="check.checkCode">
                <td>{{ check.checkCode }}</td>
                <td>{{ check.severity }}</td>
                <td>
                  <span class="status" :class="check.passed ? 'paid' : 'cancelled'">
                    {{ check.passed ? '通过' : '未通过' }}
                  </span>
                </td>
                <td>{{ check.observed }}</td>
                <td>{{ check.expected }}</td>
                <td>{{ check.detail }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>
    </template>
    <div v-else-if="!loading" class="state">尚无导入批次。请先放置本地文件并触发导入。</div>

    <p class="fine-print">
      Olist 无直接欺诈标签；IEEE-CIS 的 isFraud 只属于其自身交易域。两者不拼接、不互相补标。
    </p>
  </section>
</template>
