<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api, formatTime } from '../api.js'

const props = defineProps({
  alertId: { type: String, required: true },
  user: { type: Object, default: null },
})
const emit = defineEmits(['navigate'])

const alert = ref(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const submitting = ref(false)
const form = reactive({ reviewResult: 'INCONCLUSIVE', comment: '' })
const linkedWorkOrders = ref([])
const candidateData = ref(null)
const selectedConfigId = ref('')
const workOrderBusy = ref(false)
const workOrderError = ref('')

const canReview = computed(() => {
  const roles = props.user?.roles || []
  return roles.includes('REVIEWER') || roles.includes('ADMIN')
})

const resolved = computed(() => alert.value?.alertStatus === 'RESOLVED')

async function load() {
  loading.value = true
  error.value = ''
  try {
    alert.value = await api(`/api/v1/alerts/${encodeURIComponent(props.alertId)}`)
    await loadWorkOrders()
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}

async function loadWorkOrders() {
  workOrderError.value = ''
  try {
    linkedWorkOrders.value = await api(`/api/v3/alerts/${encodeURIComponent(props.alertId)}/work-orders`)
    if (canReview.value) {
      candidateData.value = await api(
        `/api/v3/alerts/${encodeURIComponent(props.alertId)}/work-order-candidates`,
      )
    } else {
      candidateData.value = null
    }
    if (candidateData.value?.existingWorkOrder) {
      selectedConfigId.value = ''
    }
  } catch (requestError) {
    workOrderError.value = requestError.message
  }
}

async function createWorkOrder() {
  if (!selectedConfigId.value || workOrderBusy.value) return
  workOrderBusy.value = true
  workOrderError.value = ''
  try {
    const result = await api(`/api/v3/alerts/${alert.value.alertId}/work-orders`, {
      method: 'POST',
      body: { configId: Number(selectedConfigId.value) },
    })
    notice.value = result.idempotent ? '该告警已有相同配置工单，已打开原工单。' : '工单已创建。'
    emit('navigate', 'workorders', result.id)
  } catch (requestError) {
    if (requestError.code === 'WORK_ORDER_ALREADY_EXISTS_DIFFERENT_CONFIG') {
      workOrderError.value = '该告警已使用其他配置创建工单，不能静默复用。请打开已有工单。'
      await loadWorkOrders()
    } else {
      workOrderError.value = requestError.message
    }
  } finally {
    workOrderBusy.value = false
  }
}

function existingWorkOrder() {
  return candidateData.value?.existingWorkOrder || linkedWorkOrders.value[0] || null
}

async function submitReview() {
  if (!alert.value) return
  submitting.value = true
  error.value = ''
  notice.value = ''
  try {
    await api(`/api/v1/alerts/${alert.value.alertId}/reviews`, {
      method: 'POST',
      body: {
        reviewResult: form.reviewResult,
        comment: form.comment,
        expectedVersion: alert.value.version,
      },
    })
    notice.value = '复核已记录，历史已追加。'
    form.comment = ''
    await load()
  } catch (requestError) {
    if (requestError.code === 'ALERT_VERSION_CONFLICT') {
      error.value = '版本已变化，请先刷新详情再提交。'
      await load()
    } else if (requestError.code === 'ALERT_ALREADY_RESOLVED') {
      error.value = '该告警已解决，不能继续复核。'
      await load()
    } else {
      error.value = requestError.message
    }
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <button type="button" class="link back" @click="emit('navigate', 'alerts')">← 返回异常队列</button>
        <h1>告警详情 #{{ alertId }}</h1>
        <p class="muted">复核结论只代表本次人工判断，不是客观欺诈真值，也不自动成为训练标签。</p>
      </div>
      <button type="button" class="ghost" @click="load">刷新</button>
    </header>

    <p v-if="error" class="error-box" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-box" role="status">{{ notice }}</p>
    <div v-if="loading" class="state">正在加载告警详情…</div>

    <template v-else-if="alert">
      <div class="detail-grid">
        <article class="card">
          <h2>风险信号</h2>
          <dl class="facts">
            <dt>处置状态</dt><dd><span class="status" :class="alert.alertStatus.toLowerCase()">{{ alert.alertStatus }}</span></dd>
            <dt>风险等级</dt><dd><span class="risk" :class="alert.riskLevel.toLowerCase()">{{ alert.riskLevel }}</span></dd>
            <dt>规则分</dt><dd>{{ Number(alert.score).toFixed(4) }}（阈值 {{ Number(alert.threshold).toFixed(2) }}）</dd>
            <dt>模型</dt><dd>{{ alert.modelType }} / {{ alert.modelVersion }}</dd>
            <dt>原因码</dt>
            <dd>
              <span v-for="code in alert.reasonCodes" :key="code" class="code">{{ code }}</span>
              <span v-if="!alert.reasonCodes?.length" class="muted">无</span>
            </dd>
            <dt>版本</dt><dd>{{ alert.version }}</dd>
            <dt>创建时间</dt><dd>{{ formatTime(alert.createdAt) }}</dd>
          </dl>
        </article>

        <article class="card">
          <h2>关联订单</h2>
          <dl v-if="alert.order" class="facts">
            <dt>订单</dt><dd>{{ alert.order.orderId }}<br /><small>{{ alert.order.orderNo }}</small></dd>
            <dt>客户引用</dt><dd>{{ alert.order.customerRef }}</dd>
            <dt>邮箱（掩码）</dt><dd>{{ alert.order.customerEmailMasked || '—' }}</dd>
            <dt>业务状态</dt><dd>{{ alert.order.status }}</dd>
            <dt>金额</dt><dd>{{ alert.order.amount }} {{ alert.order.currency }}</dd>
            <dt>来源</dt><dd>{{ alert.order.dataOrigin }}</dd>
            <dt>下单时间</dt><dd>{{ formatTime(alert.order.orderedAt) }}</dd>
          </dl>
        </article>
      </div>

      <article class="card">
        <h2>人工复核</h2>
        <p v-if="!canReview" class="muted">当前账号没有复核权限。请使用复核员或管理员账号。</p>
        <p v-else-if="resolved" class="muted">该告警已解决，只能查看历史。</p>
        <form v-else class="review-form" @submit.prevent="submitReview">
          <label>
            <span>复核结论</span>
            <select v-model="form.reviewResult">
              <option value="INCONCLUSIVE">证据不足（REVIEWING）</option>
              <option value="CONFIRMED">确认风险（RESOLVED）</option>
              <option value="REJECTED">排除风险（RESOLVED）</option>
            </select>
          </label>
          <label class="grow">
            <span>备注（虚构、非敏感，最长 500）</span>
            <textarea v-model="form.comment" maxlength="500" rows="3" placeholder="例如：演示复核，证据不足"></textarea>
          </label>
          <button type="submit" class="primary" :disabled="submitting">
            {{ submitting ? '提交中…' : '提交复核' }}
          </button>
        </form>

        <div class="timeline">
          <p v-if="!alert.reviews?.length" class="muted">暂无复核记录。</p>
          <div v-for="review in alert.reviews" :key="review.reviewId" class="timeline-item">
            <strong>{{ review.reviewResult }}</strong>
            <span class="muted">{{ review.reviewerId }} · {{ formatTime(review.createdAt) }}</span>
            <p>{{ review.comment || '无备注' }}</p>
            <small>{{ review.previousStatus }} → {{ review.newStatus }}</small>
          </div>
        </div>
      </article>

      <article class="card">
        <h2>关联工单</h2>
        <p v-if="workOrderError" class="error-box" role="alert">{{ workOrderError }}</p>

        <div v-if="existingWorkOrder()" class="work-order-existing">
          <p class="muted">该告警已存在工单；同一告警只能有一个工单。</p>
          <button type="button" class="link" @click="emit('navigate', 'workorders', existingWorkOrder().id)">
            打开工单 #{{ existingWorkOrder().id }}（{{ existingWorkOrder().status }}）
          </button>
        </div>

        <template v-else-if="canReview && candidateData?.actionable">
          <p class="muted">必须显式选择一个已发布且匹配的配置；系统不会自动猜选。</p>
          <div v-if="!candidateData.candidates?.length" class="state">
            没有匹配当前告警的 ACTIVE 配置。请先在“工单配置”发布配置。
          </div>
          <div v-else class="review-form">
            <label class="grow">
              <span>工单配置</span>
              <select v-model="selectedConfigId" data-testid="config-select">
                <option value="">请选择匹配配置</option>
                <option v-for="candidate in candidateData.candidates" :key="candidate.configId" :value="String(candidate.configId)">
                  {{ candidate.code }} · {{ candidate.name }} · v{{ candidate.publishedVersion }}
                  · SLA {{ candidate.slaMinutes }} 分钟{{ candidate.requiresReview ? ' · 需复核' : '' }}
                </option>
              </select>
            </label>
            <button type="button" class="primary" :disabled="!selectedConfigId || workOrderBusy" @click="createWorkOrder">
              {{ workOrderBusy ? '创建中…' : '创建工单' }}
            </button>
          </div>
        </template>

        <p v-else-if="!canReview" class="muted">当前账号只读，不能创建或处理工单。</p>
        <p v-else class="muted">该告警当前不可转工单（仅 OPEN / REVIEWING 可创建）。</p>

        <div v-if="linkedWorkOrders.length" class="linked-orders">
          <h3>告警关联记录</h3>
          <button v-for="order in linkedWorkOrders" :key="order.id" type="button" class="link"
            @click="emit('navigate', 'workorders', order.id)">
            工单 #{{ order.id }} · {{ order.status }} · {{ order.configCode }} v{{ order.configVersionNo }}
          </button>
        </div>
      </article>
    </template>
  </section>
</template>
