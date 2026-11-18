import fs from 'node:fs'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

// Optional local browser-test tool; not a production frontend dependency.
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core')
const root = fileURLToPath(new URL('..', import.meta.url))
const outputDir = `${root}runtime`
fs.mkdirSync(outputDir, { recursive: true })

const base = 'http://127.0.0.1:5173'
const demoPassword = 'Reviewer-Demo-2026!'
const adminPassword = 'Admin-Demo-2026!'
const rememberKey = 'fraud_demo.remembered_username'
const errors = []
const expectedHealthResponses = []
const apiBase = 'http://127.0.0.1:8080'
let adminCookie = ''
let adminXsrf = ''
let adminCsrf = null
let browserConfigId = null

function adminCookieHeader() {
  return [adminCookie, adminXsrf ? `XSRF-TOKEN=${adminXsrf}` : ''].filter(Boolean).join('; ')
}

function captureSessionCookie(response) {
  const values = typeof response.headers.getSetCookie === 'function'
    ? response.headers.getSetCookie()
    : [response.headers.get('set-cookie')].filter(Boolean)
  for (const value of values) {
    const session = /JSESSIONID=([^;]+)/.exec(value)
    if (session) adminCookie = `JSESSIONID=${session[1]}`
    const xsrf = /XSRF-TOKEN=([^;]+)/.exec(value)
    if (xsrf) adminXsrf = xsrf[1]
  }
}

async function ensureAdminSession() {
  if (adminCookie && adminCsrf) return
  const csrfResponse = await fetch(`${apiBase}/api/v2/auth/csrf`, {
    headers: adminCookie ? { Cookie: adminCookieHeader() } : {},
  })
  assert(csrfResponse.ok, `Admin CSRF bootstrap failed (HTTP ${csrfResponse.status})`)
  captureSessionCookie(csrfResponse)
  const csrfBody = await csrfResponse.json()
  adminCsrf = { header: csrfBody.headerName, token: csrfBody.token }

  const loginResponse = await fetch(`${apiBase}/api/v2/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      [adminCsrf.header]: adminCsrf.token,
      ...(adminCookie ? { Cookie: adminCookieHeader() } : {}),
    },
    body: JSON.stringify({ username: 'demo_admin', password: 'Admin-Demo-2026!' }),
  })
  assert(loginResponse.ok, `Admin login failed (HTTP ${loginResponse.status})`)
  captureSessionCookie(loginResponse)
  assert(adminCookie, 'Admin login did not return a JSESSIONID')

  // Login rotates the session; the pre-login CSRF token is no longer valid.
  const postLoginCsrf = await fetch(`${apiBase}/api/v2/auth/csrf`, {
    headers: { Cookie: adminCookieHeader() },
  })
  assert(postLoginCsrf.ok, `Admin CSRF refresh failed (HTTP ${postLoginCsrf.status})`)
  captureSessionCookie(postLoginCsrf)
  const postLoginBody = await postLoginCsrf.json()
  adminCsrf = { header: postLoginBody.headerName, token: postLoginBody.token }
}

async function adminRequest(path, method = 'GET', body = null) {
  await ensureAdminSession()
  const headers = { ...(adminCookie ? { Cookie: adminCookieHeader() } : {}) }
  if (body !== null) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') headers[adminCsrf.header] = adminCsrf.token
  const response = await fetch(`${apiBase}${path}`, {
    method,
    headers,
    body: body === null ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let payload = null
  try {
    payload = text ? JSON.parse(text) : null
  } catch {
    payload = null
  }
  assert(response.ok, `Admin API ${method} ${path} failed (HTTP ${response.status}): ${text}`)
  return payload
}

async function createBrowserConfig() {
  const payload = await adminRequest('/api/v3/work-order-configs', 'POST', {
    code: `BROWSER_M2_${Date.now()}`,
    name: '浏览器验收工单配置',
    description: '仅用于本地浏览器验收，虚构数据。',
    priority: 3,
    trigger: {
      sourceSystems: ['SYNTHETIC'],
      riskLevels: ['LOW', 'MEDIUM', 'HIGH'],
      alertStatuses: ['OPEN', 'REVIEWING'],
      reasonCodes: [],
    },
    riskScoreThreshold: 0.3,
    slaMinutes: 120,
    requiresReview: false,
    assignRole: 'REVIEWER',
  })
  await adminRequest(`/api/v3/work-order-configs/${payload.id}/publish`, 'POST', {
    expectedVersion: payload.version,
  })
  return payload.id
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}

function scanFrontendForIndexedDb() {
  const files = []
  const hits = []
  const visit = (directory) => {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const path = `${directory}/${entry.name}`
      if (entry.isDirectory()) {
        visit(path)
      } else if (/\.(vue|js|css|html)$/.test(entry.name)) {
        files.push(path)
        const source = fs.readFileSync(path, 'utf8')
        if (/indexedDB|IDBDatabase|IDBObjectStore|IDBTransaction|IDBFactory/i.test(source)) {
          hits.push(path)
        }
      }
    }
  }
  visit(`${root}frontend/src`)
  return { scanned: files.length, hits }
}

const indexedDbScan = scanFrontendForIndexedDb()
assert(indexedDbScan.hits.length === 0, `Frontend source uses IndexedDB: ${indexedDbScan.hits.join(', ')}`)

async function noHorizontalOverflow(page, label) {
  await page.waitForFunction(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
    null,
    { timeout: 3000 },
  ).catch(() => {})
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  assert(!overflow, `${label} has horizontal overflow`)
}

async function capture(page, name, label) {
  await noHorizontalOverflow(page, label)
  await page.screenshot({ path: `${outputDir}/${name}`, fullPage: true })
}

async function loginLayout(page) {
  return page.evaluate(() => {
    const rect = (selector) => {
      const element = document.querySelector(selector)
      if (!element) return null
      const box = element.getBoundingClientRect()
      return {
        left: box.left,
        right: box.right,
        top: box.top,
        bottom: box.bottom,
        width: box.width,
        display: getComputedStyle(element).display,
      }
    }
    return {
      visual: rect('.login-visual'),
      panel: rect('.login-panel'),
      brand: rect('.login-visual__brand'),
      copy: rect('.login-visual__copy'),
      foot: rect('.login-visual__foot'),
      inner: rect('.login-panel__inner'),
    }
  })
}

async function shellLayout(page) {
  return page.evaluate(() => {
    const rect = (selector) => {
      const element = document.querySelector(selector)
      if (!element) return null
      const box = element.getBoundingClientRect()
      return { left: box.left, right: box.right, top: box.top, width: box.width }
    }
    return {
      sidebar: rect('.sidebar'),
      workspace: rect('.workspace'),
      topbar: rect('.topbar'),
      content: rect('.content'),
    }
  })
}

async function storageSnapshot(page) {
  return page.evaluate(() => {
    const read = (store) => {
      const result = {}
      for (let index = 0; index < store.length; index += 1) {
        const key = store.key(index)
        result[key] = store.getItem(key)
      }
      return result
    }
    return {
      local: read(localStorage),
      session: read(sessionStorage),
    }
  })
}

function assertNoPassword(storage, password) {
  const serialized = JSON.stringify(storage)
  assert(!serialized.includes(password), 'Password was found in Web Storage')
}

async function drawerState(page) {
  return page.evaluate(() => {
    const sidebar = document.querySelector('#app-sidebar')
    const sidebarBox = sidebar.getBoundingClientRect()
    const content = document.querySelector('#main-content')
    const contentBox = content.getBoundingClientRect()
    const menu = document.querySelector('.mobile-menu')
    const active = document.activeElement
    return {
      open: sidebarBox.left >= -1 && sidebarBox.right > 0,
      closed: sidebarBox.right <= 1,
      sidebarRight: sidebarBox.right,
      contentLeft: contentBox.left,
      ariaHidden: sidebar.getAttribute('aria-hidden'),
      inert: sidebar.inert === true,
      expanded: menu.getAttribute('aria-expanded'),
      backdrop: Boolean(document.querySelector('.sidebar-backdrop')),
      focusInsideSidebar: sidebar.contains(active),
      activeLabel: active?.getAttribute?.('aria-label') || '',
      activeClass: typeof active?.className === 'string' ? active.className : '',
    }
  })
}

async function waitForDrawer(page, open) {
  await page.waitForFunction((expectedOpen) => {
    const sidebar = document.querySelector('#app-sidebar')
    if (!sidebar) return false
    const box = sidebar.getBoundingClientRect()
    return expectedOpen
      ? box.left >= -1 && box.right > 0 && Boolean(document.querySelector('.sidebar-backdrop'))
      : box.right <= 1 && !document.querySelector('.sidebar-backdrop')
  }, open, { timeout: 3000 })
}

async function assertDrawerOpen(page) {
  await waitForDrawer(page, true)
  const state = await drawerState(page)
  assert(state.open && state.backdrop, 'Mobile drawer did not open')
  assert(state.ariaHidden === null, 'Open drawer must not be aria-hidden')
  assert(state.inert === false, 'Open drawer must not be inert')
  assert(state.expanded === 'true', 'Menu aria-expanded is not true while open')
  assert(state.focusInsideSidebar, 'Focus did not move into the open drawer')
  assert(state.activeClass.includes('sidebar-close-mobile'), 'Focus did not land on the close button')
}

async function assertDrawerClosed(page, label) {
  await waitForDrawer(page, false)
  const state = await drawerState(page)
  assert(!state.backdrop, `${label}: backdrop remains after close`)
  assert(state.closed, `${label}: drawer is not fully off-canvas after close`)
  assert(state.ariaHidden === 'true', `${label}: closed drawer is not aria-hidden`)
  assert(state.inert === true, `${label}: closed drawer is not inert`)
  assert(state.expanded === 'false', `${label}: menu aria-expanded is not false after close`)
  assert(!state.focusInsideSidebar, `${label}: focus remains inside closed drawer`)
  assert(state.contentLeft >= -1, `${label}: content starts outside the viewport`)
  assert(state.sidebarRight <= 1, `${label}: drawer overlaps the content area`)
}

async function openDrawer(page, keyboard = false) {
  const menu = page.getByRole('button', { name: '打开导航' })
  await menu.focus()
  if (keyboard) {
    await page.keyboard.press('Enter')
  } else {
    await menu.click()
  }
  await assertDrawerOpen(page)
}

async function closeDrawerWithEscape(page) {
  await page.keyboard.press('Escape')
  await assertDrawerClosed(page, 'Escape close')
  assert((await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))) === '打开导航',
    'Escape close did not return focus to the opener')
}

async function closeDrawerWithBackdrop(page) {
  await page.mouse.click(370, 500)
  await assertDrawerClosed(page, 'Backdrop close')
  assert((await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))) === '打开导航',
    'Backdrop close did not return focus to the opener')
}

async function closeDrawerWithButton(page) {
  await page.locator('.sidebar-close-mobile').click()
  await assertDrawerClosed(page, 'Close button')
  assert((await page.evaluate(() => document.activeElement?.getAttribute('aria-label'))) === '打开导航',
    'Close button did not return focus to the opener')
}

async function assertClosedDrawerTabsStayOutside(page) {
  const menu = page.getByRole('button', { name: '打开导航' })
  await menu.focus()
  for (let index = 0; index < 8; index += 1) {
    await page.keyboard.press('Tab')
    const state = await drawerState(page)
    assert(!state.focusInsideSidebar, `Tab stop ${index + 1} entered the closed drawer`)
  }
}

async function navigateDesktop(page, label, heading) {
  await page.getByRole('button', { name: label, exact: true }).click()
  await page.getByRole('heading', { name: heading }).waitFor()
}

async function navigateMobile(page, label, heading, screenshotName) {
  await openDrawer(page)
  await page.getByRole('button', { name: label, exact: true }).click()
  await page.getByRole('heading', { name: heading }).waitFor()
  await assertDrawerClosed(page, `Mobile ${label}`)
  await capture(page, screenshotName, `Mobile ${label}`)
}

async function waitForNoLoading(page, loadingText) {
  await page.waitForFunction((text) => !document.body.innerText.includes(text), loadingText)
}

async function switchUser(username, password) {
  const csrf = await page.evaluate(async () => {
    const response = await fetch('/api/v2/auth/csrf', { credentials: 'same-origin' })
    return response.json()
  })
  const status = await page.evaluate(async ({ username, password, token, header }) => {
    const response = await fetch('/api/v2/auth/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', [header]: token },
      body: JSON.stringify({ username, password }),
    })
    return response.status
  }, { username, password, token: csrf.token, header: csrf.headerName })
  assert(status === 200, `Switch to ${username} failed (HTTP ${status})`)
  await page.reload({ waitUntil: 'networkidle' })
}

const browser = await chromium.launch({
  executablePath: process.env.BROWSER_EXECUTABLE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true,
})
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 })
const page = await context.newPage()
page.on('pageerror', (error) => errors.push(error.message))
page.on('response', (response) => {
  if (response.url().endsWith('/api/v1/health') && response.status() === 503) {
    expectedHealthResponses.push(response.url())
  }
})

try {
  await page.goto(base, { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '登录复核台' }).waitFor()
  await page.evaluate((key) => localStorage.removeItem(key), rememberKey)
  await page.reload({ waitUntil: 'networkidle' })

  const usernameInput = page.locator('input[name="username"]')
  const passwordInput = page.locator('input[name="password"]')
  assert((await usernameInput.getAttribute('autocomplete')) === 'username', 'Username autocomplete is missing')
  assert((await passwordInput.getAttribute('autocomplete')) === 'current-password', 'Password autocomplete is missing')
  assert((await usernameInput.getAttribute('name')) === 'username', 'Username name is missing')
  assert((await passwordInput.getAttribute('name')) === 'password', 'Password name is missing')
  await page.screenshot({ path: `${outputDir}/ui-v3-login-1440.png`, fullPage: true })
  await noHorizontalOverflow(page, 'Desktop login')
  const desktopLoginLayout = await loginLayout(page)
  assert(desktopLoginLayout.visual && desktopLoginLayout.panel, 'Desktop login columns are missing')
  assert(desktopLoginLayout.visual.right <= desktopLoginLayout.panel.left + 1, 'Login columns overlap')
  assert(desktopLoginLayout.copy.bottom <= desktopLoginLayout.foot.top - 4, 'Login visual copy overlaps footer')
  assert(desktopLoginLayout.brand.bottom < desktopLoginLayout.copy.top, 'Login visual brand overlaps copy')

  // A failed login with no prior remembered name must write nothing.
  await usernameInput.fill('demo_reviewer')
  await passwordInput.fill('wrong-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('alert').waitFor()
  const failedMe = await page.evaluate(async () => {
    const response = await fetch('/api/v2/auth/me', { credentials: 'same-origin' })
    return response.status
  })
  assert(failedMe === 401, `Failed login left an authenticated session (HTTP ${failedMe})`)
  const failedStorage = await storageSnapshot(page)
  assert(!failedStorage.local[rememberKey], 'Failed login saved a new username')

  // Seed a remembered name, then try a different name with a bad password:
  // the failure must not overwrite the existing remembered username.
  await page.evaluate(({ key, value }) => localStorage.setItem(key, value), {
    key: rememberKey,
    value: 'demo_admin',
  })
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '登录复核台' }).waitFor()
  assert((await usernameInput.inputValue()) === 'demo_admin', 'Remembered username was not restored')
  assert(await page.locator('input[name="remember-account"]').isChecked(), 'Remembered checkbox was not restored')
  await usernameInput.fill('demo_reviewer')
  await passwordInput.fill('wrong-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('alert').waitFor()
  const failedStorageWithOldName = await storageSnapshot(page)
  assert(
    failedStorageWithOldName.local[rememberKey] === 'demo_admin',
    'Failed login overwrote the existing remembered username',
  )

  // Demo account entry only fills the username, never the password.
  await page.locator('details.demo-accounts summary').click()
  await page.getByRole('button', { name: /复核员/ }).click()
  assert((await usernameInput.inputValue()) === 'demo_reviewer', 'Demo account did not fill username')
  assert((await passwordInput.inputValue()) === '', 'Demo account must not fill password')
  await passwordInput.fill(demoPassword)
  await page.locator('input[name="remember-account"]').check()
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('heading', { name: '订单概览' }).waitFor()
  await page.getByRole('cell', { name: /DEMO-O-0001/ }).waitFor()
  if (await page.locator('table').getByText('已确认欺诈').count()) {
    throw new Error('Order page must not claim confirmed fraud')
  }

  const shellAtLogin = await shellLayout(page)
  assert(Math.abs(shellAtLogin.sidebar.width - 232) <= 1, 'Desktop sidebar width is not 232px')
  assert(shellAtLogin.workspace.left >= shellAtLogin.sidebar.right - 1, 'Desktop workspace overlaps sidebar')
  assert((await page.locator('#app-sidebar').getAttribute('aria-hidden')) === null,
    'Desktop sidebar must not be aria-hidden')
  assert(await page.locator('#app-sidebar').evaluate((element) => element.inert === false),
    'Desktop sidebar must not be inert')
  assert((await page.locator('.mobile-menu').getAttribute('aria-controls')) === 'app-sidebar',
    'Menu aria-controls does not reference the sidebar')
  assert((await page.locator('.mobile-menu').getAttribute('aria-expanded')) === 'false',
    'Desktop menu aria-expanded should be false')

  const firstSessionCookie = (await context.cookies()).find((cookie) => cookie.name === 'JSESSIONID')
  assert(firstSessionCookie, 'Login did not create a JSESSIONID cookie')
  assert(firstSessionCookie.httpOnly, 'JSESSIONID must be HttpOnly')
  assert(!firstSessionCookie.value.includes(demoPassword), 'Password must never appear in a cookie')

  const storageAfterLogin = await storageSnapshot(page)
  assertNoPassword(storageAfterLogin, demoPassword)
  assert(
    storageAfterLogin.local[rememberKey] === 'demo_reviewer',
    'Remembered username was not stored only after successful login',
  )
  browserConfigId = await createBrowserConfig()
  await capture(page, 'ui-v3-orders-1440.png', 'Desktop orders')

  // Orders: filter, reset, and scoped table/filter/header styling.
  await page.locator('.filters select').first().selectOption('PAID')
  const ordersResponsePromise = page.waitForResponse((response) => (
    response.request().method() === 'GET' && response.url().includes('/api/v1/orders?')
  ))
  await page.getByRole('button', { name: '筛选', exact: true }).click()
  const ordersResponse = await ordersResponsePromise
  assert(ordersResponse.ok(), `Order filter failed (HTTP ${ordersResponse.status()})`)
  assert(new URL(ordersResponse.url()).searchParams.get('status') === 'PAID', 'Order filter did not send status')
  await waitForNoLoading(page, '正在加载订单')
  const filteredOrderStatuses = await page.locator('tbody .status').allTextContents()
  for (const value of filteredOrderStatuses) {
    assert(value.trim() === 'PAID', `Order filter returned ${value}`)
  }
  const resetOrdersPromise = page.waitForResponse((response) => (
    response.request().method() === 'GET' && response.url().includes('/api/v1/orders?')
  ))
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await resetOrdersPromise
  await waitForNoLoading(page, '正在加载订单')

  const shellStyles = await page.evaluate(() => {
    const get = (selector) => {
      const element = document.querySelector(selector)
      if (!element) return null
      const style = getComputedStyle(element)
      return {
        display: style.display,
        borderTopWidth: style.borderTopWidth,
        borderRadius: style.borderRadius,
        backgroundColor: style.backgroundColor,
        fontSize: style.fontSize,
      }
    }
    return {
      head: get('.shell .page-head'),
      filters: get('.shell .filters'),
      table: get('.shell table'),
      card: get('.shell .card'),
    }
  })
  assert(shellStyles.head?.display === 'flex', 'Shell page head is not laid out')
  assert(shellStyles.filters && shellStyles.filters.borderTopWidth !== '0px', 'Shell filters lost their border')
  assert(shellStyles.table && parseFloat(shellStyles.table.fontSize) >= 12, 'Shell table styling is missing')

  // A second login on the existing session must rotate the session id.
  const csrf = await page.evaluate(async () => {
    const response = await fetch('/api/v2/auth/csrf', { credentials: 'same-origin' })
    return response.json()
  })
  const rotateStatus = await page.evaluate(async ({ token, header, password }) => {
    const response = await fetch('/api/v2/auth/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', [header]: token },
      body: JSON.stringify({ username: 'demo_reviewer', password }),
    })
    return response.status
  }, { token: csrf.token, header: csrf.headerName, password: demoPassword })
  assert(rotateStatus === 200, `Second login failed (HTTP ${rotateStatus})`)
  const rotatedCookie = (await context.cookies()).find((cookie) => cookie.name === 'JSESSIONID')
  assert(rotatedCookie, 'Second login removed the session cookie')
  assert(rotatedCookie.value !== firstSessionCookie.value, 'Login did not rotate the session id')

  // Alerts: filter and existing detail/review interaction.
  await navigateDesktop(page, '异常队列', '异常队列')
  await capture(page, 'ui-v3-alerts-1440.png', 'Desktop alerts')
  await page.locator('.filters select').nth(1).selectOption('HIGH')
  const alertsResponsePromise = page.waitForResponse((response) => (
    response.request().method() === 'GET' && response.url().includes('/api/v1/alerts?')
  ))
  await page.getByRole('button', { name: '筛选', exact: true }).click()
  const alertsResponse = await alertsResponsePromise
  assert(alertsResponse.ok(), `Alert filter failed (HTTP ${alertsResponse.status()})`)
  assert(new URL(alertsResponse.url()).searchParams.get('riskLevel') === 'HIGH', 'Alert filter did not send riskLevel')
  await waitForNoLoading(page, '正在加载告警')
  const filteredRiskLevels = await page.locator('tbody .risk').allTextContents()
  for (const value of filteredRiskLevels) {
    assert(value.trim() === 'HIGH', `Alert filter returned ${value}`)
  }
  await page.locator('.filters select').nth(0).selectOption('')
  await page.locator('.filters select').nth(1).selectOption('')
  const resetAlertsPromise = page.waitForResponse((response) => (
    response.request().method() === 'GET' && response.url().includes('/api/v1/alerts?')
  ))
  await page.getByRole('button', { name: '筛选', exact: true }).click()
  await resetAlertsPromise
  await waitForNoLoading(page, '正在加载告警')
  await page.locator('tbody tr').first().getByRole('button').click()
  await page.getByRole('heading', { name: /告警详情/ }).waitFor()

  await page.getByLabel('复核结论').selectOption('INCONCLUSIVE')
  await page.getByLabel(/备注/).fill('M1 收尾验收：虚构复核记录')
  await page.getByRole('button', { name: '提交复核' }).click()
  await page.getByText('复核已记录，历史已追加。').waitFor()
  await capture(page, 'ui-v3-alert-detail-1440.png', 'Desktop alert detail')

  await switchUser('demo_admin', adminPassword)
  // M4: run before work-order creation so the work-order snapshot is genuinely empty.
  await navigateDesktop(page, '效能总览', '效能总览')
  await page.locator('.chart-svg').first().waitFor()
  const realOverview = await page.evaluate(async () => {
    const response = await fetch('/api/v3/dashboard/overview', { credentials: 'same-origin' })
    return response.json()
  })
  assert(realOverview.snapshot.alertsHasData === true,
    'Dashboard alert snapshot should have data from the synthetic seed')
  assert(realOverview.snapshot.workOrdersHasData === false,
    'Dashboard work-order snapshot should be empty before work-order creation')
  await page.getByText('当前没有工单记录。').waitFor()
  assert((await page.locator('.chart-data').count()) >= 3,
    'Dashboard accessibility data tables are missing')
  await capture(page, 'ui-v4-dashboard-1440.png', 'Admin dashboard')
  await page.getByRole('button', { name: '近 30 天' }).click()
  await page.getByText('当前范围没有订单；可切换到近 366 天查看演示种子。').waitFor()
  await noHorizontalOverflow(page, 'Dashboard empty range')

  const dashboardPattern = '**/api/v3/dashboard/overview?*'
  const fixture = JSON.parse(JSON.stringify(realOverview))
  fixture.imports = {
    hasData: true,
    batches: [
      { batchId: 'M4-FAILED', sourceSystem: 'OLIST', status: 'FAILED',
        startedAt: '2026-11-03T00:00:00Z', acceptedRows: 0, rejectedRows: 7 },
      { batchId: 'M4-PARTIAL', sourceSystem: 'IEEE_CIS', status: 'PARTIAL',
        startedAt: '2026-11-04T00:00:00Z', acceptedRows: 10, rejectedRows: 2 },
    ],
  }
  fixture.dq = {
    hasData: true,
    scope: 'ALL_ENDED_BATCHES',
    totalChecks: 3,
    evaluatedChecks: 2,
    infoChecks: 1,
    infoFailed: 0,
    failedError: 0,
    failedWarn: 1,
    passRate: 0.5,
    bySource: [
      { sourceSystem: 'IEEE_CIS', hasData: true, totalChecks: 1, evaluatedChecks: 1,
        infoChecks: 0, infoFailed: 0, failedError: 0, failedWarn: 1, passRate: 0.0 },
      { sourceSystem: 'OLIST', hasData: true, totalChecks: 2, evaluatedChecks: 1,
        infoChecks: 1, infoFailed: 0, failedError: 0, failedWarn: 0, passRate: 1.0 },
    ],
  }
  await page.route(dashboardPattern, (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(fixture),
  }))
  await page.getByRole('button', { name: '刷新' }).click()
  await page.locator('.import-batch.status-failed').first().waitFor()
  assert((await page.locator('.import-batch.status-failed').count()) === 1,
    'Failed import batch is not visually marked')
  assert((await page.locator('.import-status--status-failed').innerText()).includes('失败'),
    'Failed import status text is missing')
  assert((await page.locator('.import-batch.status-failed .import-track i').first()
    .evaluate((element) => getComputedStyle(element).backgroundColor)) === 'rgb(184, 75, 75)',
    'Failed import accepted bar does not use the failure color')
  assert((await page.locator('.import-chart table').count()) === 1,
    'Import batch accessibility table is missing')
  assert((await page.locator('.dq-source-table').count()) === 1,
    'DQ source-domain table is missing')
  assert((await page.locator('.dq-source-table').innerText()).includes('IEEE_CIS')
    && (await page.locator('.dq-source-table').innerText()).includes('OLIST'),
    'DQ source-domain table does not separate IEEE_CIS and OLIST')
  assert((await page.getByText('通过率（ERROR/WARN）', { exact: true }).count()) === 0,
    'Global DQ pass rate is still presented as a primary card')
  await capture(page, 'ui-v4-dashboard-imports-1440.png', 'Dashboard import batch states')
  await page.unroute(dashboardPattern)

  await page.route(dashboardPattern, (route) => route.fulfill({
    status: 500,
    contentType: 'application/json',
    body: '{"code":"INTERNAL_ERROR","message":"fixture"}',
  }))
  await page.getByRole('button', { name: '刷新' }).click()
  await page.locator('.error-box').waitFor()
  await page.unroute(dashboardPattern)
  await page.getByRole('button', { name: '刷新' }).click()
  await page.getByRole('heading', { name: '效能总览' }).waitFor()

  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('heading', { name: '效能总览' }).waitFor()
  await noHorizontalOverflow(page, 'Dashboard mobile')
  await page.screenshot({ path: `${outputDir}/ui-v4-dashboard-390.png`, fullPage: true })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await switchUser('demo_reviewer', demoPassword)
  await navigateDesktop(page, '异常队列', '异常队列')
  await waitForNoLoading(page, '正在加载告警')
  await page.locator('tbody tr').first().getByRole('button').click()
  await page.getByRole('heading', { name: /告警详情/ }).waitFor()
  // M2: explicit alert conversion, claim/start/resolve, and read-only config preview.
  await page.getByTestId('config-select').selectOption(String(browserConfigId))
  await page.getByRole('button', { name: '创建工单', exact: true }).click()
  await page.getByRole('heading', { name: /工单 #/ }).waitFor()
  await page.getByRole('button', { name: '领取工单', exact: true }).click()
  await page.getByRole('button', { name: '开始处理', exact: true }).waitFor()
  await page.getByRole('button', { name: '开始处理', exact: true }).click()
  await page.getByTestId('resolution-note').waitFor()
  await page.getByTestId('resolution-note').fill('M2 浏览器验收：已完成处置')
  await page.getByTestId('resolve-order').click()
  await page.locator('.facts .status').getByText('RESOLVED', { exact: true }).waitFor()
  await capture(page, 'ui-v3-work-order-detail-1440.png', 'Desktop work order detail')

  // M2 cancel: admin-only, explicit irreversible confirmation. Dismissing the
  // in-page dialog must send no /transitions request; confirming sends exactly one.
  await switchUser('demo_admin', adminPassword)
  await navigateDesktop(page, '异常队列', '异常队列')
  await waitForNoLoading(page, '正在加载告警')
  await page.locator('tbody tr').filter({ hasText: 'DEMO-O-0001' }).first().getByRole('button').click()
  await page.getByRole('heading', { name: /告警详情/ }).waitFor()
  await page.getByTestId('config-select').selectOption(String(browserConfigId))
  await page.getByRole('button', { name: '创建工单', exact: true }).click()
  await page.getByRole('heading', { name: /工单 #/ }).waitFor()

  let cancelTransitionRequests = 0
  const countCancelTransition = (request) => {
    if (request.method() === 'POST' && request.url().includes('/transitions')) {
      cancelTransitionRequests += 1
    }
  }
  page.on('request', countCancelTransition)
  await page.getByTestId('cancel-note').fill('M2 浏览器验收：取消不可恢复')
  await page.getByRole('button', { name: '取消工单', exact: true }).click()
  await page.getByTestId('cancel-confirm-submit').waitFor()
  assert(await page.getByTestId('cancel-confirm-submit').isVisible(),
    'Cancel confirmation dialog did not open')
  await page.getByTestId('cancel-confirm-back').click()
  await page.getByTestId('cancel-confirm-submit').waitFor({ state: 'detached' })
  assert(cancelTransitionRequests === 0, 'Dismissing cancel confirmation sent a transition request')
  assert((await page.locator('.facts .status').innerText()).includes('OPEN'),
    'Dismissing cancel confirmation changed the work-order status')

  await page.getByRole('button', { name: '取消工单', exact: true }).click()
  await page.getByTestId('cancel-confirm-submit').click()
  await page.locator('.facts .status').getByText('CANCELLED', { exact: true }).waitFor()
  assert(cancelTransitionRequests === 1,
    `Confirming cancel sent ${cancelTransitionRequests} transition requests`)
  page.off('request', countCancelTransition)
  await capture(page, 'ui-v3-work-order-cancel-1440.png', 'Cancelled work order')

  // M3: admin operational log window, default WARN, pause stops polling.
  const logButton = page.getByRole('button', { name: /打开运行日志/ })
  await logButton.waitFor()
  const firstLogResponse = page.waitForResponse((response) => (
    response.url().includes('/api/v3/ops/logs?') && response.request().method() === 'GET'
  ))
  await logButton.click()
  await firstLogResponse
  await page.getByRole('heading', { name: '运行日志' }).waitFor()
  assert(await page.locator('.ops-log-filters select').first().isVisible(),
    'Operational log filters are missing')
  assert((await page.locator('.ops-log-filters select').nth(1).inputValue()) === 'WARN',
    'Operational log default minimum level is not WARN')
  const infoLogResponse = page.waitForResponse((response) => (
    response.url().includes('/api/v3/ops/logs?') && response.url().includes('minLevel=INFO')
  ))
  await page.locator('.ops-log-filters select').nth(1).selectOption('INFO')
  await infoLogResponse
  await page.locator('.ops-log-item').first().waitFor()
  assert((await page.locator('.ops-log-item').count()) >= 1, 'Operational log INFO filter returned no rows')
  await capture(page, 'ui-v3-ops-log-1440.png', 'Admin operational log window')
  const logRequests = []
  const countLogRequests = (request) => {
    if (request.method() === 'GET' && request.url().includes('/api/v3/ops/logs?')) {
      logRequests.push(request.url())
    }
  }
  page.on('request', countLogRequests)
  await page.getByRole('button', { name: '暂停' }).click()
  const pausedLogCount = logRequests.length
  await page.waitForTimeout(5500)
  assert(logRequests.length === pausedLogCount, 'Paused operational log kept polling')
  await page.getByRole('button', { name: '继续' }).click()
  await page.getByRole('button', { name: '清屏' }).click()
  await page.getByText('当前筛选条件下没有日志。').waitFor()
  page.off('request', countLogRequests)
  await page.getByRole('button', { name: '关闭运行日志' }).click()
  await page.getByRole('heading', { name: '运行日志' }).waitFor({ state: 'detached' })

  await page.setViewportSize({ width: 390, height: 844 })
  await logButton.click()
  await page.getByRole('heading', { name: '运行日志' }).waitFor()
  await noHorizontalOverflow(page, 'Operational log mobile')
  await page.screenshot({ path: `${outputDir}/ui-v3-ops-log-390.png`, fullPage: true })
  await page.getByRole('button', { name: '关闭运行日志' }).click()
  await page.setViewportSize({ width: 1440, height: 1000 })

  await switchUser('demo_reviewer', demoPassword)
  assert((await page.getByRole('button', { name: /打开运行日志/ }).count()) === 0,
    'Reviewer must not see the operational log entry')
  assert((await page.getByRole('button', { name: '效能总览', exact: true }).count()) === 0,
    'Reviewer must not see the admin dashboard entry')

  await navigateDesktop(page, '工单配置', '工单配置')
  await waitForNoLoading(page, '正在加载工单配置')
  assert((await page.getByRole('button', { name: '新建配置', exact: true }).count()) === 0,
    'Reviewer must not see the create-config action')
  await page.getByRole('button', { name: '预览匹配', exact: true }).click()
  await page.getByText(/匹配告警数/).waitFor()
  await capture(page, 'ui-v3-work-order-configs-1440.png', 'Desktop work order configs')

  await navigateDesktop(page, '工单队列', '工单队列')
  await waitForNoLoading(page, '正在加载工单')
  assert((await page.locator('tbody tr').count()) >= 1, 'Resolved work order is missing from the queue')
  await capture(page, 'ui-v3-work-orders-1440.png', 'Desktop work orders')

  // Data quality: reviewer sees the read-only import boundary and card styling.
  await navigateDesktop(page, '数据质量', '数据质量')
  await waitForNoLoading(page, '正在加载质量摘要')
  assert(await page.getByRole('button', { name: /导入 Olist/ }).isDisabled(), 'Reviewer can trigger Olist import')
  assert(await page.getByRole('button', { name: /导入 IEEE-CIS/ }).isDisabled(), 'Reviewer can trigger IEEE import')
  await page.getByText('只有管理员可以触发导入。').waitFor()
  assert((await page.locator('.shell .card').count()) > 0, 'Data quality cards are missing')
  await capture(page, 'ui-v3-quality-1440.png', 'Desktop quality')

  // Models: reviewer cannot train; cards and empty state remain visible.
  await navigateDesktop(page, '模型评估', '模型评估')
  await waitForNoLoading(page, '正在加载模型与评测')
  assert(await page.getByRole('button', { name: '训练并评测' }).isDisabled(), 'Reviewer can trigger training')
  await page.getByText('只有管理员可以触发训练。').waitFor()
  assert((await page.locator('.shell .card').count()) >= 2, 'Model cards are missing')
  await capture(page, 'ui-v3-models-1440.png', 'Desktop models')

  // Health: refresh interaction and expected stub degradation.
  await navigateDesktop(page, '系统健康', '系统健康')
  await page.getByText('DEGRADED', { exact: true }).waitFor()
  await page.getByText('database=BYPASS', { exact: false }).waitFor()
  const healthResponsePromise = page.waitForResponse((response) => (
    response.url().endsWith('/api/v1/health') && response.status() === 503
  ))
  await page.getByRole('button', { name: '刷新' }).click()
  await healthResponsePromise
  await capture(page, 'ui-v3-health-1440.png', 'Desktop health')

  // Collapsed desktop navigation remains usable and does not overflow.
  await page.getByRole('button', { name: '折叠侧栏' }).click()
  await page.getByRole('button', { name: '展开侧栏' }).waitFor()
  await page.getByRole('button', { name: '系统健康' }).click()
  await page.getByRole('heading', { name: '系统健康' }).waitFor()
  await noHorizontalOverflow(page, 'Collapsed desktop')
  const collapsedShell = await shellLayout(page)
  assert(Math.abs(collapsedShell.sidebar.width - 76) <= 1, 'Collapsed sidebar width is not 76px')
  assert(collapsedShell.workspace.left >= collapsedShell.sidebar.right - 1, 'Collapsed workspace overlaps sidebar')
  await page.screenshot({ path: `${outputDir}/ui-v3-shell-collapsed-1440.png`, fullPage: true })
  await page.getByRole('button', { name: '展开侧栏' }).click()

  // Compact desktop widths and a short viewport: 901-980px is the narrowest
  // non-drawer band and must not introduce document-level horizontal overflow.
  for (const width of [901, 940, 980]) {
    await page.setViewportSize({ width, height: 600 })
    await navigateDesktop(page, '工单队列', '工单队列')
    await waitForNoLoading(page, '正在加载工单')
    await noHorizontalOverflow(page, `Work orders at ${width}px`)
    await capture(page, `ui-v3-work-orders-${width}.png`, `Work orders at ${width}px`)
  }
  await page.setViewportSize({ width: 1440, height: 1000 })

  // Mobile: drawer is fully off-canvas before any interaction.
  await page.setViewportSize({ width: 390, height: 844 })
  await assertDrawerClosed(page, 'Initial mobile')
  await assertClosedDrawerTabsStayOutside(page)

  // Keyboard open: focus enters the close button.
  await openDrawer(page, true)
  await page.screenshot({ path: `${outputDir}/ui-v3-drawer-open-390.png`, fullPage: true })
  await closeDrawerWithEscape(page)

  // Backdrop and explicit close button both close and restore focus.
  await openDrawer(page)
  await closeDrawerWithBackdrop(page)
  await openDrawer(page)
  await closeDrawerWithButton(page)

  // Opening then choosing a route closes the drawer; content is not covered.
  await openDrawer(page)
  await page.getByRole('button', { name: '订单概览', exact: true }).click()
  await page.getByRole('heading', { name: '订单概览' }).waitFor()
  await assertDrawerClosed(page, 'Mobile route change')
  await capture(page, 'ui-v3-orders-390.png', 'Mobile orders')
  await assertClosedDrawerTabsStayOutside(page)

  await navigateMobile(page, '异常队列', '异常队列', 'ui-v3-alerts-390.png')
  await navigateMobile(page, '工单队列', '工单队列', 'ui-v3-work-orders-390.png')
  await page.locator('tbody tr').first().getByRole('button').click()
  await page.getByRole('heading', { name: /工单 #/ }).waitFor()
  await capture(page, 'ui-v3-work-order-detail-390.png', 'Mobile work order detail')
  await navigateMobile(page, '工单配置', '工单配置', 'ui-v3-work-order-configs-390.png')
  await navigateMobile(page, '数据质量', '数据质量', 'ui-v3-quality-390.png')
  await navigateMobile(page, '模型评估', '模型评估', 'ui-v3-models-390.png')
  await navigateMobile(page, '系统健康', '系统健康', 'ui-v3-health-390.png')

  // Logout retains the remembered username; unchecking deletes immediately.
  const cookieBeforeLogout = (await context.cookies()).find((cookie) => cookie.name === 'JSESSIONID')
  await page.getByRole('button', { name: '退出登录' }).click()
  await page.getByRole('heading', { name: '登录复核台' }).waitFor()
  await page.screenshot({ path: `${outputDir}/ui-v3-login-390.png`, fullPage: true })
  await noHorizontalOverflow(page, 'Mobile login')
  const mobileLoginLayout = await loginLayout(page)
  assert(mobileLoginLayout.visual?.display === 'none', 'Mobile login must hide the large visual')
  assert(Math.abs(mobileLoginLayout.panel.width - 390) <= 1, 'Mobile login panel does not fill the viewport')
  assert(mobileLoginLayout.inner.width <= 390, 'Mobile login form exceeds the viewport')
  assert((await usernameInput.inputValue()) === 'demo_reviewer', 'Logout did not retain the remembered username')
  assert(await page.locator('input[name="remember-account"]').isChecked(), 'Remembered checkbox was not restored')

  const oldCookieStatus = await fetch('http://127.0.0.1:8080/api/v1/orders', {
    headers: { Cookie: `JSESSIONID=${cookieBeforeLogout.value}` },
  })
  assert(oldCookieStatus.status === 401, `Old session still works after logout (HTTP ${oldCookieStatus.status})`)

  await page.locator('input[name="remember-account"]').uncheck()
  const storageAfterUncheck = await storageSnapshot(page)
  assert(!storageAfterUncheck.local[rememberKey], 'Unchecking remember did not remove the username immediately')
  await page.reload({ waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '登录复核台' }).waitFor()
  assert((await usernameInput.inputValue()) === '', 'Unchecked username reappeared after refresh')

  if (expectedHealthResponses.length < 1) throw new Error('Expected 503 health response was not observed')
  if (errors.length) throw new Error(`Browser page errors: ${errors.join('; ')}`)

  const anonymous = await browser.newPage()
  await anonymous.goto(base, { waitUntil: 'networkidle' })
  await anonymous.getByRole('heading', { name: '登录复核台' }).waitFor()
  await anonymous.close()

  console.log(JSON.stringify({
    indexedDb: `not used in frontend/src (${indexedDbScan.scanned} source files scanned)`,
    loginLayout: 'passed (1440 + 390)',
    autocomplete: 'passed (username/current-password)',
    rememberAccount: 'passed (only after success; failure preserves old value; uncheck immediate)',
    passwordStorage: 'passed (not in Web Storage or cookies; no IndexedDB use in source)',
    failedLogin: 'passed (no authenticated session; no new remembered name)',
    sessionRotation: 'passed (second login changed JSESSIONID)',
    logoutInvalidation: 'passed (old JSESSIONID returns 401)',
    fivePages: 'passed (orders filter/reset, alerts filter + detail review, quality read-only, models read-only, health refresh)',
    m2WorkOrders: 'passed (explicit alert conversion, claim/start/resolve, cancel dismissal/confirmation, read-only config preview, queue/detail at 1440/901/940/980/390)',
    m3OpsLog: 'passed (ADMIN entry, WARN default, pause stops polling, clear view, 1440/390, reviewer hidden)',
    m4Dashboard: 'passed (ADMIN entry, SVG charts + data tables, alert/work-order snapshot asymmetry, 30-day empty state, FAILED import status/color, DQ bySource primary and no global pass-rate card, load error, 1440/390, reviewer hidden)',
    shellStyles: 'passed (scoped page-head/filters/table/card; screenshots at 1440 and 390)',
    drawer: 'passed (off-canvas, aria-expanded/controls, inert/aria-hidden, focus return, backdrop/Escape/button/nav close)',
    keyboard: 'passed (keyboard open, Escape close, closed drawer not tabbable)',
    mobileOverflow: 'passed (orders, alerts, work orders, work-order detail, configs, quality, models, health, dashboard)',
    anonymousRedirect: 'passed',
    pageErrors: errors,
  }, null, 2))
} finally {
  await browser.close()
}
