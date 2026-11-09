<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api, formatScore, formatTime } from '../api.js'

const props = defineProps({
  user: { type: Object, default: null },
})

const items = ref([])
const detail = ref(null)
const versions = ref([])
const preview = ref(null)
const selectedId = ref(null)
const createMode = ref(false)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const filters = reactive({ keyword: '', status: '' })
const form = reactive({
  code: '',
  name: '',
  description: '',
  priority: 3,
  riskScoreThreshold: 0.7,
  slaMinutes: 120,
  requiresReview: true,
  assignRole: 'REVIEWER',
  trigger: { sourceSystems: [], riskLevels: [], alertStatuses: [], reasonCodes: [] },
  expectedVersion: 1,
})

const sourceSystems = ['SYNTHETIC', 'OLIST']
const riskLevels = ['LOW', 'MEDIUM', 'HIGH']
const alertStatuses = ['OPEN', 'REVIEWING']
const reasonCodes = ['HIGH_AMOUNT', 'REPEATED_PAYMENT_ATTEMPTS', 'NEW_ACCOUNT', 'HIGH_VELOCITY']
const statusLabels = { DRAFT: '草稿', ACTIVE: '已发布', DISABLED: '已停用' }

const isAdmin = computed(() => (props.user?.roles || []).includes('ADMIN'))
const canSave = computed(() => isAdmin.value && (createMode.value
  || ['DRAFT', 'ACTIVE'].includes(detail.value?.status)))
const canPublish = computed(() => isAdmin.value && !createMode.value
  && ['DRAFT', 'ACTIVE'].includes(detail.value?.status))
const canDisable = computed(() => isAdmin.value && !createMode.value
  && detail.value?.status === 'ACTIVE')
const canPreview = computed(() => !createMode.value && detail.value?.status !== 'DISABLED'
  && Boolean(detail.value?.publishedVersion))

function cloneTrigger(trigger) {
  return {
    sourceSystems: [...(trigger?.sourceSystems || [])],
    riskLevels: [...(trigger?.riskLevels || [])],
    alertStatuses: [...(trigger?.alertStatuses || [])],
    reasonCodes: [...(trigger?.reasonCodes || [])],
  }
}

function resetForm(value) {
  const source = value || {}
  form.code = source.code || ''
  form.name = source.name || ''
  form.description = source.description || ''
  form.priority = source.priority ?? 3
  form.riskScoreThreshold = Number(source.riskScoreThreshold ?? 0.7)
  form.slaMinutes = source.slaMinutes ?? 120
  form.requiresReview = Boolean(source.requiresReview)
  form.assignRole = source.assignRole || 'REVIEWER'
  form.trigger = cloneTrigger(source.trigger)
  form.expectedVersion = source.version || 1
}

async function loadList() {
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({ page: '0', size: '50' })
    if (filters.keyword.trim()) params.set('keyword', filters.keyword.trim())
    if (filters.status) params.set('status', filters.status)
    const result = await api(`/api/v3/work-order-configs?${params}`)
    items.value = result.items || []
    if (!createMode.value && !detail.value && items.value.length) {
      await selectConfig(items.value[0].id)
    }
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    loading.value = false
  }
}

async function selectConfig(id) {
  error.value = ''
  notice.value = ''
  preview.value = null
  versions.value = []
  try {
    detail.value = await api(`/api/v3/work-order-configs/${encodeURIComponent(id)}`)
    selectedId.value = detail.value.id
    createMode.value = false
    resetForm(detail.value)
  } catch (requestError) {
    error.value = requestError.message
  }
}

function startCreate() {
  createMode.value = true
  selectedId.value = null
  detail.value = null
  versions.value = []
  preview.value = null
  resetForm(null)
  error.value = ''
  notice.value = ''
}

function payload(includeCode) {
  const body = {
    name: form.name.trim(),
    description: form.description.trim() || null,
    priority: Number(form.priority),
    trigger: cloneTrigger(form.trigger),
    riskScoreThreshold: Number(form.riskScoreThreshold),
    slaMinutes: Number(form.slaMinutes),
    requiresReview: Boolean(form.requiresReview),
    assignRole: form.assignRole,
  }
  if (includeCode) {
    body.code = form.code.trim()
  } else {
    body.expectedVersion = Number(form.expectedVersion)
  }
  return body
}

async function save() {
  if (!isAdmin.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = createMode.value
      ? await api('/api/v3/work-order-configs', { method: 'POST', body: payload(true) })
      : await api(`/api/v3/work-order-configs/${detail.value.id}`, {
        method: 'PUT',
        body: payload(false),
      })
    detail.value = result
    selectedId.value = result.id
    createMode.value = false
    resetForm(result)
    notice.value = '配置草稿已保存。'
    await loadList()
  } catch (requestError) {
    handleError(requestError)
  } finally {
    busy.value = false
  }
}

async function publish() {
  if (!canPublish.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    detail.value = await api(`/api/v3/work-order-configs/${detail.value.id}/publish`, {
      method: 'POST',
      body: { expectedVersion: Number(form.expectedVersion) },
    })
    resetForm(detail.value)
    notice.value = `已发布版本 v${detail.value.publishedVersion}。`
    await loadList()
  } catch (requestError) {
    handleError(requestError)
  } finally {
    busy.value = false
  }
}

async function disable() {
  if (!canDisable.value) return
  if (!window.confirm('停用后不能重新启用或发布；请确认。')) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    detail.value = await api(`/api/v3/work-order-configs/${detail.value.id}/disable`, {
      method: 'POST',
      body: { expectedVersion: Number(form.expectedVersion) },
    })
    resetForm(detail.value)
    notice.value = '配置已停用。停用是终态。'
    await loadList()
  } catch (requestError) {
    handleError(requestError)
  } finally {
    busy.value = false
  }
}

async function loadVersions() {
  if (createMode.value || !detail.value) return
  error.value = ''
  try {
    versions.value = await api(`/api/v3/work-order-configs/${detail.value.id}/versions`)
  } catch (requestError) {
    error.value = requestError.message
  }
}

async function runPreview() {
  if (!canPreview.value) return
  busy.value = true
  error.value = ''
  preview.value = null
  try {
    preview.value = await api('/api/v3/work-order-configs/preview', {
      method: 'POST',
      body: { configId: detail.value.id },
    })
  } catch (requestError) {
    handleError(requestError)
  } finally {
    busy.value = false
  }
}

function handleError(requestError) {
  if (requestError.code === 'WORK_ORDER_CONFIG_VERSION_CONFLICT') {
    error.value = '配置版本已变化，已刷新最新版本；请重新确认后操作。'
    if (!createMode.value) selectConfig(detail.value.id)
  } else {
    error.value = requestError.message
  }
}

onMounted(loadList)
</script>

<template>
  <section>
    <header class="page-head">
      <div>
        <h1>工单配置</h1>
        <p class="muted">
          配置按发布版本生效。草稿未发布前不参与匹配；风险阈值语义固定为“风险分阈值（非欺诈概率）”。
        </p>
      </div>
      <button type="button" class="ghost" :disabled="loading" @click="loadList">刷新</button>
    </header>

    <p v-if="error" class="error-box" role="alert">{{ error }}</p>
    <p v-if="notice" class="success-box" role="status">{{ notice }}</p>
    <div v-if="loading" class="state">正在加载工单配置…</div>

    <div v-else class="wo-config-layout">
      <article class="card">
        <div class="section-head">
          <h2>配置列表</h2>
          <button v-if="isAdmin" type="button" class="primary" @click="startCreate">新建配置</button>
        </div>
        <form class="filters" @submit.prevent="loadList">
          <label class="grow">
            <span>关键字</span>
            <input v-model="filters.keyword" maxlength="64" placeholder="code 或名称" />
          </label>
          <label>
            <span>状态</span>
            <select v-model="filters.status">
              <option value="">全部</option>
              <option value="DRAFT">草稿</option>
              <option value="ACTIVE">已发布</option>
              <option value="DISABLED">已停用</option>
            </select>
          </label>
          <button type="submit" class="ghost">筛选</button>
        </form>

        <div v-if="!items.length" class="state">没有可见的配置。</div>
        <div v-else class="table-wrap">
          <table class="data-table">
            <thead>
              <tr><th>编码</th><th>名称</th><th>状态</th><th>发布版本</th><th>更新时间</th></tr>
            </thead>
            <tbody>
              <tr v-for="item in items" :key="item.id" :class="{ selected: selectedId === item.id }">
                <td><button type="button" class="link" @click="selectConfig(item.id)">{{ item.code }}</button></td>
                <td>{{ item.name }}</td>
                <td><span class="status" :class="item.status.toLowerCase()">{{ statusLabels[item.status] || item.status }}</span></td>
                <td>{{ item.publishedVersion ? `v${item.publishedVersion}` : '—' }}</td>
                <td>{{ formatTime(item.updatedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>

      <article v-if="createMode || detail" class="card">
        <div class="section-head">
          <div>
            <h2>{{ createMode ? '新建配置' : detail.code }}</h2>
            <p v-if="!createMode" class="muted">
              {{ statusLabels[detail.status] || detail.status }}
              <span v-if="detail.publishedVersion"> · 已发布 v{{ detail.publishedVersion }}</span>
              <span v-if="detail.hasUnpublishedChanges"> · 有未发布修改</span>
            </p>
          </div>
          <span v-if="!createMode" class="status" :class="detail.status.toLowerCase()">{{ detail.status }}</span>
        </div>

        <form v-if="isAdmin" class="stack-form" @submit.prevent="save">
          <div class="form-grid">
            <label>
              <span>配置编码</span>
              <input v-model="form.code" :disabled="!createMode" maxlength="64" placeholder="例如 HIGH_RISK_REVIEW" />
            </label>
            <label>
              <span>配置名称</span>
              <input v-model="form.name" maxlength="128" />
            </label>
            <label class="span-2">
              <span>描述</span>
              <textarea v-model="form.description" maxlength="500" rows="2"></textarea>
            </label>
            <label>
              <span>优先级（1–5）</span>
              <input v-model.number="form.priority" type="number" min="1" max="5" />
            </label>
            <label>
              <span>SLA（分钟，1–10080）</span>
              <input v-model.number="form.slaMinutes" type="number" min="1" max="10080" />
            </label>
            <label>
              <span>风险分阈值（非欺诈概率）</span>
              <input v-model.number="form.riskScoreThreshold" type="number" min="0" max="1" step="0.0001" />
            </label>
            <label>
              <span>分派角色</span>
              <select v-model="form.assignRole">
                <option value="REVIEWER">REVIEWER</option>
                <option value="ADMIN">ADMIN</option>
              </select>
            </label>
            <label class="checkbox-line span-2">
              <input v-model="form.requiresReview" type="checkbox" />
              <span>完成工单必须关联结论性复核记录</span>
            </label>
          </div>

          <div class="trigger-grid">
            <fieldset>
              <legend>来源系统（任一匹配）</legend>
              <label v-for="option in sourceSystems" :key="option" class="checkbox-line">
                <input v-model="form.trigger.sourceSystems" type="checkbox" :value="option" />
                <span>{{ option }}</span>
              </label>
            </fieldset>
            <fieldset>
              <legend>风险等级（任一匹配）</legend>
              <label v-for="option in riskLevels" :key="option" class="checkbox-line">
                <input v-model="form.trigger.riskLevels" type="checkbox" :value="option" />
                <span>{{ option }}</span>
              </label>
            </fieldset>
            <fieldset>
              <legend>告警状态（任一匹配）</legend>
              <label v-for="option in alertStatuses" :key="option" class="checkbox-line">
                <input v-model="form.trigger.alertStatuses" type="checkbox" :value="option" />
                <span>{{ option }}</span>
              </label>
            </fieldset>
            <fieldset>
              <legend>原因码（任一匹配）</legend>
              <label v-for="option in reasonCodes" :key="option" class="checkbox-line">
                <input v-model="form.trigger.reasonCodes" type="checkbox" :value="option" />
                <span>{{ option }}</span>
              </label>
            </fieldset>
          </div>
          <p class="muted small">同一字段内为 OR，不同字段间为 AND；至少选择一个实际过滤条件。</p>

          <div class="action-row">
            <button type="submit" class="primary" :disabled="busy || !canSave">
              {{ busy ? '保存中…' : '保存草稿' }}
            </button>
            <button type="button" class="ghost" :disabled="busy || !canPublish" @click="publish">发布</button>
            <button type="button" class="ghost danger-text" :disabled="busy || !canDisable" @click="disable">停用</button>
          </div>
        </form>

        <div v-else class="facts">
          <dt>配置编码</dt><dd>{{ detail.code }}</dd>
          <dt>名称</dt><dd>{{ detail.name || '—' }}</dd>
          <dt>描述</dt><dd>{{ detail.description || '—' }}</dd>
          <dt>风险分阈值</dt><dd>{{ formatScore(detail.riskScoreThreshold) }} <small>风险分阈值（非欺诈概率）</small></dd>
          <dt>SLA</dt><dd>{{ detail.slaMinutes }} 分钟</dd>
          <dt>完成需要复核</dt><dd>{{ detail.requiresReview ? '是' : '否' }}</dd>
          <dt>分派角色</dt><dd>{{ detail.assignRole }}</dd>
          <dt>已发布版本</dt><dd>{{ detail.publishedVersion ? `v${detail.publishedVersion}` : '—' }}</dd>
        </div>

        <div v-if="!createMode" class="action-row">
          <button v-if="canPreview" type="button" class="ghost" :disabled="busy" @click="runPreview">预览匹配</button>
          <button type="button" class="ghost" @click="loadVersions">版本记录</button>
        </div>

        <div v-if="versions.length" class="subsection">
          <h3>版本记录</h3>
          <div class="table-wrap">
            <table class="data-table">
              <thead><tr><th>版本</th><th>名称</th><th>阈值</th><th>发布时间</th><th>发布人</th></tr></thead>
              <tbody>
                <tr v-for="version in versions" :key="version.id">
                  <td>v{{ version.versionNo }}</td>
                  <td>{{ version.name }}</td>
                  <td>{{ formatScore(version.riskScoreThreshold) }}</td>
                  <td>{{ formatTime(version.publishedAt) }}</td>
                  <td>{{ version.publishedBy }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="preview" class="subsection preview-box">
          <h3>匹配预览（已发布版本 v{{ preview.publishedVersion }}）</h3>
          <p>匹配告警数：<strong>{{ preview.totalMatched }}</strong></p>
          <p class="muted">仅展示计数与分组，不展示原始告警行。</p>
          <div class="preview-groups">
            <div>
              <h4>按风险等级</h4>
              <p v-if="!preview.groupsByRiskLevel?.length" class="muted">无匹配</p>
              <p v-for="group in preview.groupsByRiskLevel" :key="group.key">{{ group.key }}：{{ group.count }}</p>
            </div>
            <div>
              <h4>按来源系统</h4>
              <p v-if="!preview.groupsBySourceSystem?.length" class="muted">无匹配</p>
              <p v-for="group in preview.groupsBySourceSystem" :key="group.key">{{ group.key }}：{{ group.count }}</p>
            </div>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>
