<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, formatScore, formatTime } from '../api.js'

const props = defineProps({
  workOrderId: { type: String, required: true },
  user: { type: Object, default: null },
})
const emit = defineEmits(['navigate'])

const detail = ref(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const busy = ref(false)
const assignees = ref([])
const selectedAssignee = ref('')
const noteText = ref('')
const cancelText = ref('')
const cancelConfirmOpen = ref(false)
const resolutionReviewId = ref('')

const roles = computed(() => props.user?.roles || [])
const isAdmin = computed(() => roles.value.includes('ADMIN'))
const currentUser = computed(() => props.user?.username || '')
// The server is authoritative for role/assignee/assignRole/terminal actions;
// do not duplicate the permission matrix in Vue.
const canClaim = computed(() => detail.value?.actions?.canClaim === true)
const canAssignSelf = computed(() => detail.value?.actions?.canAssignSelf === true)
const canAssign = computed(() => detail.value?.actions?.canAssign === true)
const canStart = computed(() => detail.value?.actions?.canStart === true)
const canResolve = computed(() => detail.value?.actions?.canResolve === true)
const canCancel = computed(() => detail.value?.actions?.canCancel === true)
const canNote = computed(() => detail.value?.actions?.canAddNote === true)

const slaLabels = {
  ON_TRACK: '按期',
  OVERDUE: '已逾期',
  MET: '按期完成',
  BREACHED: '逾期完成',
  CANCELLED: '已取消',
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await api(`/api/v3/work-orders/${encodeURIComponent(props.workOrderId)}`)
    resolutionReviewId.value = detail.value.resolutionReviewId
      ? String(detail.value.resolutionReviewId) : ''
    if (isAdmin.value) {
      assignees.value = await api('/api/v3/work-orders/assignees')
    }
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}

function resetMessages() {
  error.value = ''
  notice.value = ''
}

async function refreshAfter(message) {
  notice.value = message
  await load()
}

function handleError(requestError) {
  if (requestError.code === 'WORK_ORDER_VERSION_CONFLICT') {
    error.value = '工单版本已变化，已刷新详情；请重新确认后操作。'
    load()
  } else if (requestError.code === 'WORK_ORDER_TERMINAL') {
    error.value = '工单已处于终态，不能继续修改。'
    load()
  } else {
    error.value = requestError.message
  }
}

async function transition(action, payload = {}) {
  if (busy.value) return
  busy.value = true
  resetMessages()
  try {
    detail.value = await api(`/api/v3/work-orders/${detail.value.id}/transitions`, {
      method: 'POST',
      body: {
        transition: action,
        expectedVersion: detail.value.version,
        ...payload,
      },
    })
    notice.value = '工单状态已更新。'
    resolutionReviewId.value = detail.value.resolutionReviewId
      ? String(detail.value.resolutionReviewId) : ''
  } catch (requestError) {
    handleError(requestError)
  } finally {
    busy.value = false
  }
}

async function claim() {
  await transition('CLAIM')
}

async function start() {
  await transition('START')
}

async function resolve() {
  if (detail.value.requiresReview && !resolutionReviewId.value) {
    error.value = '该工单需要选择结论性复核记录。'
    return
  }
  if (!noteText.value.trim()) {
    error.value = '完成工单必须填写处理结论备注。'
    return
  }
  const body = { note: noteText.value }
  if (resolutionReviewId.value) {
    body.resolutionReviewId = Number(resolutionReviewId.value)
  }
  await transition('RESOLVE', body)
  noteText.value = ''
}

function cancel() {
  if (busy.value) return
  if (!cancelText.value.trim()) {
    error.value = '取消工单必须填写取消原因。'
    return
  }
  // Do not send anything until the user explicitly confirms the irreversible
  // terminal transition in the dialog below.
  cancelConfirmOpen.value = true
}

function dismissCancel() {
  cancelConfirmOpen.value = false
}

async function confirmCancel() {
  if (busy.value) return
  cancelConfirmOpen.value = false
  await transition('CANCEL', { note: cancelText.value })
  if (detail.value?.status === 'CANCELLED') {
    cancelText.value = ''
  }
}

async function addNote() {
  if (!noteText.value.trim()) {
    error.value = '备注不能为空。'
    return
  }
  busy.value = true
  resetMessages()
  try {
    detail.value = await api(`/api/v3/work-orders/${detail.value.id}/notes`, {
      method: 'POST',
      body: { note: noteText.value, expectedVersion: detail.value.version },
    })
    noteText.value = ''
    notice.value = '处理备注已追加到事件时间线。'
  } catch (requestError) {
    handleError(requestError)
  } finally {
    busy.value = false
  }
}

async function assignSelf() {
  await assign(currentUser.value)
}

async function assignSelected() {
  if (!selectedAssignee.value) {
    error.value = '请选择有效处理人。'
    return
  }
  await assign(selectedAssignee.value)
}

async function assign(assigneeId) {
  busy.value = true
  resetMessages()
  try {
    detail.value = await api(`/api/v3/work-orders/${detail.value.id}/assign`, {
      method: 'POST',
      body: { assigneeId, expectedVersion: detail.value.version },
    })
    notice.value = '工单已分派。'
    selectedAssignee.value = ''
  } catch (requestError) {
    handleError(requestError)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <button type="button" class="link back" @click="emit('navigate', 'workorders')">← 返回工单队列</button>
        <h1>工单 #{{ workOrderId }}</h1>
        <p class="muted">处理备注只记录风险告警处置过程；不是客户资料或模型训练标签。</p>
      </div>
      <button type="button" class="ghost" :disabled="loading" @click="load">刷新</button>
    </header>

    <p v-if="error" class="error-box" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-box" role="status">{{ notice }}</p>
    <div v-if="loading" class="state">正在加载工单详情…</div>

    <template v-else-if="detail">
      <div class="detail-grid">
        <article class="card">
          <h2>工单概览</h2>
          <dl class="facts">
            <dt>状态</dt><dd><span class="status" :class="detail.status.toLowerCase()">{{ detail.status }}</span></dd>
            <dt>SLA</dt><dd><span class="sla" :class="detail.slaStatus.toLowerCase()">{{ slaLabels[detail.slaStatus] || detail.slaStatus }}</span></dd>
            <dt>截止时间</dt><dd>{{ formatTime(detail.dueAt) }}</dd>
            <dt>分派角色</dt><dd>{{ detail.assignRole }}</dd>
            <dt>处理人</dt><dd>{{ detail.assigneeId || '未分派' }}</dd>
            <dt>配置版本</dt><dd>v{{ detail.configVersionNo }}</dd>
            <dt>需要复核</dt><dd>{{ detail.requiresReview ? '是' : '否' }}</dd>
            <dt>版本</dt><dd>{{ detail.version }}</dd>
            <dt>创建时间</dt><dd>{{ formatTime(detail.createdAt) }}</dd>
            <dt v-if="detail.closedAt">关闭时间</dt><dd v-if="detail.closedAt">{{ formatTime(detail.closedAt) }}</dd>
          </dl>
        </article>

        <article class="card">
          <h2>关联告警</h2>
          <dl class="facts">
            <dt>告警</dt>
            <dd><button type="button" class="link" @click="emit('navigate', 'alerts', detail.alertId)">#{{ detail.alertId }}</button></dd>
            <dt>订单</dt><dd>{{ detail.alert?.orderId }}</dd>
            <dt>告警状态</dt><dd>{{ detail.alert?.alertStatus }}</dd>
            <dt>风险等级</dt><dd>{{ detail.alert?.riskLevel }}</dd>
            <dt>风险分</dt><dd>{{ formatScore(detail.alert?.score) }}</dd>
            <dt>原因码</dt>
            <dd>
              <span v-for="code in detail.alert?.reasonCodes || []" :key="code" class="code">{{ code }}</span>
              <span v-if="!detail.alert?.reasonCodes?.length" class="muted">无</span>
            </dd>
          </dl>
        </article>
      </div>

      <article class="card">
        <h2>配置发布快照</h2>
        <dl class="facts" v-if="detail.configSnapshot">
          <dt>编码</dt><dd>{{ detail.configSnapshot.code }} · v{{ detail.configSnapshot.configVersion }}</dd>
          <dt>名称</dt><dd>{{ detail.configSnapshot.name || '—' }}</dd>
          <dt>风险分阈值</dt><dd>{{ formatScore(detail.configSnapshot.riskScoreThreshold) }} <small>风险分阈值（非欺诈概率）</small></dd>
          <dt>SLA</dt><dd>{{ detail.configSnapshot.slaMinutes }} 分钟</dd>
          <dt>完成需要复核</dt><dd>{{ detail.configSnapshot.requiresReview ? '是' : '否' }}</dd>
          <dt>分派角色</dt><dd>{{ detail.configSnapshot.assignRole }}</dd>
        </dl>
      </article>

      <article class="card">
        <h2>处理动作</h2>
        <div v-if="isAdmin" class="assign-row">
          <label>
            <span>分派处理人</span>
            <select v-model="selectedAssignee" data-testid="assignee-select">
              <option value="">请选择 REVIEWER/ADMIN</option>
              <option v-for="account in assignees" :key="account.username" :value="account.username">
                {{ account.username }}（{{ account.role }}）
              </option>
            </select>
          </label>
          <button type="button" class="ghost" :disabled="busy || !canAssign" @click="assignSelected">分派</button>
        </div>

        <div class="action-row">
          <button v-if="canAssignSelf" type="button" class="primary" :disabled="busy" @click="assignSelf">分派给自己</button>
          <button v-if="canClaim" type="button" class="primary" :disabled="busy" @click="claim">领取工单</button>
          <button v-if="canStart" type="button" class="primary" :disabled="busy" @click="start">开始处理</button>
          <button v-if="canCancel" type="button" class="ghost danger-text" :disabled="busy" @click="cancel">取消工单</button>
        </div>

        <div v-if="canResolve" class="action-block">
          <h3>完成工单</h3>
          <label v-if="detail.requiresReview">
            <span>结论性复核</span>
            <select v-model="resolutionReviewId" data-testid="resolution-review">
              <option value="">请选择复核记录</option>
              <option v-for="review in detail.resolutionReviewCandidates" :key="review.reviewId" :value="String(review.reviewId)">
                #{{ review.reviewId }} · {{ review.reviewResult }} · {{ formatTime(review.createdAt) }}
              </option>
            </select>
          </label>
          <p v-if="detail.requiresReview && !detail.resolutionReviewCandidates?.length" class="muted">
            当前没有可用的结论性复核记录；必须先由复核员完成复核。
          </p>
          <label class="grow">
            <span>处理结论备注（必填，最长 500）</span>
            <textarea v-model="noteText" data-testid="resolution-note" maxlength="500" rows="3" placeholder="例如：已确认风险并完成处置"></textarea>
          </label>
          <button type="button" class="primary" data-testid="resolve-order" :disabled="busy" @click="resolve">完成工单</button>
        </div>

        <div v-if="canCancel" class="action-block">
          <label class="grow">
            <span>取消原因（必填，最长 500）</span>
            <textarea v-model="cancelText" data-testid="cancel-note" maxlength="500" rows="2" placeholder="例如：重复告警"></textarea>
          </label>
        </div>

        <div v-if="canNote" class="action-block">
          <h3>追加处理备注</h3>
          <label class="grow">
            <span>备注（最长 500）</span>
            <textarea v-model="noteText" data-testid="note-text" maxlength="500" rows="2" placeholder="不会记录密码、Cookie 或请求正文"></textarea>
          </label>
          <button type="button" class="ghost" data-testid="add-note" :disabled="busy" @click="addNote">添加备注</button>
        </div>
      </article>

      <article class="card">
        <h2>关联复核</h2>
        <p v-if="detail.resolutionReview" class="muted">
          工单完成复核：#{{ detail.resolutionReview.reviewId }} · {{ detail.resolutionReview.reviewResult }}
          · {{ formatTime(detail.resolutionReview.createdAt) }}
        </p>
        <p v-else class="muted">尚未关联完成复核。</p>
        <div v-if="detail.resolutionReviewCandidates?.length" class="candidate-list">
          <h3>可用结论性复核</h3>
          <div v-for="review in detail.resolutionReviewCandidates" :key="review.reviewId" class="timeline-item">
            <strong>#{{ review.reviewId }} {{ review.reviewResult }}</strong>
            <span class="muted">{{ review.reviewerId }} · {{ formatTime(review.createdAt) }}</span>
          </div>
        </div>
      </article>

      <article class="card">
        <h2>处理事件</h2>
        <div v-if="!detail.events?.length" class="muted">暂无事件。</div>
        <div v-else class="timeline">
          <div v-for="event in detail.events" :key="event.eventId" class="timeline-item">
            <strong>{{ event.eventType }}</strong>
            <span class="muted">{{ event.actorId }} · {{ event.actorRole }} · {{ formatTime(event.createdAt) }}</span>
            <p v-if="event.note">{{ event.note }}</p>
            <small v-if="event.fromStatus || event.toStatus">
              {{ event.fromStatus || '—' }} → {{ event.toStatus || '—' }}
            </small>
          </div>
        </div>
      </article>

      <div v-if="cancelConfirmOpen" class="modal-backdrop" @click.self="dismissCancel">
        <div class="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="cancel-confirm-title">
          <h2 id="cancel-confirm-title">确认取消工单</h2>
          <p>取消后工单进入 <strong>CANCELLED</strong> 终态，<strong>不可恢复、不可重新打开</strong>。</p>
          <p class="muted">取消原因：{{ cancelText }}</p>
          <div class="confirm-actions">
            <button type="button" class="ghost" data-testid="cancel-confirm-back" :disabled="busy" @click="dismissCancel">
              返回
            </button>
            <button type="button" class="primary danger-button" data-testid="cancel-confirm-submit" :disabled="busy" @click="confirmCancel">
              确认取消
            </button>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
