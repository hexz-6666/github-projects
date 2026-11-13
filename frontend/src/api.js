let csrf = null

const buildVersion = (() => {
  const value = import.meta.env.VITE_BUILD_VERSION || 'dev'
  return /^[A-Za-z0-9._-]{1,32}$/.test(value) ? value : 'dev'
})()

export function resetCsrf() {
  csrf = null
}

function routeKey() {
  const raw = window.location.hash.replace(/^#\/?/, '').split('?')[0]
  const parts = raw.split('/').filter(Boolean)
  const name = parts[0] || 'orders'
  if (name === 'alerts') return parts[1] ? 'ALERT_DETAIL' : 'ALERTS'
  if (name === 'workorders') return parts[1] ? 'WORK_ORDER_DETAIL' : 'WORK_ORDERS'
  if (name === 'workorder-configs') return 'WORK_ORDER_CONFIGS'
  if (name === 'quality') return 'QUALITY'
  if (name === 'models') return 'MODELS'
  if (name === 'health') return 'HEALTH'
  if (name === 'login') return 'LOGIN'
  return 'ORDERS'
}

function clientCode(status) {
  if (status === 400) return 'CLIENT_HTTP_400'
  if (status === 403) return 'CLIENT_HTTP_403'
  if (status === 404) return 'CLIENT_HTTP_404'
  if (status === 409) return 'CLIENT_HTTP_409'
  if (status === 422) return 'CLIENT_HTTP_422'
  if (status === 429) return 'CLIENT_HTTP_429'
  if (status >= 500) return 'CLIENT_HTTP_5XX'
  return 'CLIENT_RESPONSE_INVALID'
}

function isUuid(value) {
  return typeof value === 'string'
    && /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(value)
}

async function reportClientError(code, relatedRequestId) {
  try {
    const token = await ensureCsrf()
    const payload = {
      code,
      route: routeKey(),
      buildVersion,
    }
    if (isUuid(relatedRequestId)) payload.relatedRequestId = relatedRequestId
    await fetch('/api/v3/ops/client-errors', {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        [token.header]: token.token,
      },
      body: JSON.stringify(payload),
    })
  } catch {
    // Client reporting is best effort and must never affect the original call.
  }
}

async function ensureCsrf() {
  if (csrf) {
    return csrf
  }
  const response = await fetch('/api/v2/auth/csrf', { credentials: 'same-origin' })
  if (!response.ok) {
    throw new Error('无法获取 CSRF 令牌')
  }
  const body = await response.json()
  csrf = { header: body.headerName, token: body.token }
  return csrf
}

export async function api(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase()
  const headers = { ...(options.headers || {}) }
  let body = options.body
  if (body !== undefined && body !== null && typeof body !== 'string') {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(body)
  }
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const token = await ensureCsrf()
    headers[token.header] = token.token
  }
  let response
  try {
    response = await fetch(path, {
      method,
      headers,
      body,
      credentials: 'same-origin',
    })
  } catch (networkError) {
    if (!path.startsWith('/api/v3/ops/')) {
      void reportClientError('CLIENT_NETWORK_ERROR', null)
    }
    throw networkError
  }
  const text = await response.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = null
    }
  }
  if (!response.ok) {
    const error = new Error(payload?.message || `请求失败（HTTP ${response.status}）`)
    error.status = response.status
    error.code = payload?.code
    error.requestId = payload?.requestId
    const opsEndpoint = path.startsWith('/api/v3/ops/')
    if (!opsEndpoint && response.status !== 401) {
      void reportClientError(clientCode(response.status), payload?.requestId)
    }
    if (response.status === 401 && !path.includes('/auth/')) {
      window.dispatchEvent(new CustomEvent('auth-expired'))
    }
    throw error
  }
  return payload
}

export function formatTime(value) {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

export function formatPercent(value) {
  if (value === null || value === undefined) {
    return '—'
  }
  return `${(Number(value) * 100).toFixed(2)}%`
}

export function formatScore(value) {
  if (value === null || value === undefined) {
    return '—'
  }
  return Number(value).toFixed(4)
}
