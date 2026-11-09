let csrf = null

export function resetCsrf() {
  csrf = null
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
  const response = await fetch(path, {
    method,
    headers,
    body,
    credentials: 'same-origin',
  })
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
