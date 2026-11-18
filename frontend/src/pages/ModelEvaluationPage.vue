<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, formatPercent, formatTime } from '../api.js'

const props = defineProps({ user: { type: Object, default: null } })

const models = ref([])
const evaluations = ref([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const training = ref(false)
const modelVersion = ref('ieee-logreg-v1')

const canTrain = computed(() => (props.user?.roles || []).includes('ADMIN'))

function formatMetric(value, digits = 4) {
  return value === null || value === undefined ? '—' : Number(value).toFixed(digits)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [modelRows, evalRows] = await Promise.all([
      api('/api/v2/models'),
      api('/api/v2/evaluations'),
    ])
    models.value = modelRows
    evaluations.value = evalRows
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}

async function train() {
  training.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await api('/api/v2/models/train', {
      method: 'POST',
      body: { modelVersion: modelVersion.value },
    })
    notice.value = `训练完成：${result.model.modelVersion}，测试 PR-AUC ${formatPercent(result.evaluation.prAuc)}。`
    await load()
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    training.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <p class="eyebrow">IEEE-CIS · 独立评测</p>
        <h1>模型评估</h1>
        <p class="muted">
          训练与评测只使用 IEEE-CIS 交易数据，按 TransactionDT 时间切分；不对 Olist 报欺诈指标。
        </p>
      </div>
      <button type="button" class="ghost" @click="load">刷新</button>
    </header>

    <p v-if="error" class="error-box" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-box" role="status">{{ notice }}</p>

    <article class="card">
      <h2>训练入口</h2>
      <p class="muted">
        需要已将 <code>train_transaction.csv</code> 放入 <code>data/external/ieee-cis</code>。
        训练按 70/15/15 时间切分，并以 card1+addr1 清除跨段实体；测试集只评估一次，阈值在验证集选择。
      </p>
      <form class="filters" @submit.prevent="train">
        <label class="grow">
          <span>模型版本</span>
          <input v-model.trim="modelVersion" maxlength="64" pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,63}" required />
        </label>
        <button type="submit" class="primary" :disabled="!canTrain || training">
          {{ training ? '训练中…' : '训练并评测' }}
        </button>
      </form>
      <p v-if="!canTrain" class="fine-print">只有管理员可以触发训练。</p>
    </article>

    <div v-if="loading" class="state">正在加载模型与评测…</div>
    <template v-else>
      <article class="card">
        <h2>已注册模型</h2>
        <div v-if="!models.length" class="muted">尚无训练模型。规则评分仍由 DEMO_RULE 提供。</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr><th>版本</th><th>类型</th><th>来源</th><th>训练行数</th><th>正类率</th><th>训练时间</th></tr>
            </thead>
            <tbody>
              <tr v-for="model in models" :key="model.modelVersion">
                <td><strong>{{ model.modelVersion }}</strong></td>
                <td>{{ model.modelType }}</td>
                <td>{{ model.sourceSystem }}</td>
                <td>{{ model.trainingRows }}</td>
                <td>{{ formatPercent(model.positiveRate) }}</td>
                <td>{{ formatTime(model.trainedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>

      <article class="card">
        <h2>评测指标</h2>
        <p class="muted">
          类别不平衡下优先看 PR-AUC、固定 FPR 的召回与 Precision@100，不能只报准确率。
        </p>
        <div v-if="!evaluations.length" class="muted">尚无评测记录。</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>版本</th><th>切分</th><th>测试行/正类</th><th>基率</th>
                <th>PR-AUC</th><th>ROC-AUC</th><th>Recall@FPR1%</th><th>Recall@FPR5%</th>
                <th>Precision@100</th><th>Brier</th><th>阈值</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="evaluation in evaluations" :key="evaluation.evalId">
                <td>{{ evaluation.modelVersion }}</td>
                <td>{{ evaluation.splitStrategy }}<span v-if="evaluation.entityGrouping && evaluation.entityGrouping !== 'NONE'" class="fine-print"> / 实体清除 {{ evaluation.validRowsDroppedForEntity || 0 }}+{{ evaluation.testRowsDroppedForEntity || 0 }}</span></td>
                <td>{{ evaluation.testRows }} / {{ evaluation.testPositives }}</td>
                <td>{{ formatPercent(evaluation.baseRate) }}</td>
                <td>{{ formatPercent(evaluation.prAuc) }}</td>
                <td>{{ formatPercent(evaluation.rocAuc) }}</td>
                <td>{{ formatPercent(evaluation.recallAtFpr1) }}</td>
                <td>{{ formatPercent(evaluation.recallAtFpr5) }}</td>
                <td>{{ formatPercent(evaluation.precisionAt100) }}</td>
                <td>{{ formatMetric(evaluation.brier) }}</td>
                <td>{{ formatMetric(evaluation.threshold, 2) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>
    </template>

    <p class="fine-print">
      模型分不是真实欺诈概率，也不是 Olist 的欺诈真值。没有 IEEE-CIS 本地文件时，训练入口会返回受控错误。
    </p>
  </section>
</template>
