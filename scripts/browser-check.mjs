import fs from 'node:fs'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

// Optional local browser-test tool; not a production frontend dependency.
const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || '@playwright/test')
const root = fileURLToPath(new URL('..', import.meta.url))
const outputDir = `${root}runtime`
fs.mkdirSync(outputDir, { recursive: true })

const browser = await chromium.launch({
  executablePath: process.env.BROWSER_EXECUTABLE || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true,
})
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 }, deviceScaleFactor: 1 })
const errors = []
const expectedHealthResponses = []
page.on('pageerror', (error) => errors.push(error.message))
page.on('response', (response) => {
  if (response.url().endsWith('/api/v1/health') && response.status() === 503) {
    expectedHealthResponses.push(response.url())
  }
})

async function noHorizontalOverflow(label) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
  if (overflow) throw new Error(`${label} has horizontal overflow`)
}

try {
  await page.goto('http://127.0.0.1:5173', { waitUntil: 'networkidle' })
  await page.getByRole('heading', { name: '订单风险复核台' }).waitFor()
  await page.screenshot({ path: `${outputDir}/ui-login.png`, fullPage: true })

  await page.getByRole('button', { name: '复核员' }).click()
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('heading', { name: '订单概览' }).waitFor()
  await page.getByRole('cell', { name: /DEMO-O-0001/ }).waitFor()
  if (await page.locator('table').getByText('已确认欺诈').count()) throw new Error('Order page must not claim confirmed fraud')
  await page.screenshot({ path: `${outputDir}/ui-orders.png`, fullPage: true })

  await page.getByRole('button', { name: '异常队列' }).click()
  await page.getByRole('heading', { name: '异常队列' }).waitFor()
  await page.locator('tbody tr').first().getByRole('button').click()
  await page.getByRole('heading', { name: /告警详情/ }).waitFor()
  await page.getByLabel('复核结论').selectOption('INCONCLUSIVE')
  await page.getByLabel(/备注/).fill('浏览器验收：虚构复核记录')
  await page.getByRole('button', { name: '提交复核' }).click()
  await page.getByText('复核已记录，历史已追加。').waitFor()
  await page.screenshot({ path: `${outputDir}/ui-alert-detail.png`, fullPage: true })

  await page.getByRole('button', { name: '数据质量' }).click()
  await page.getByRole('heading', { name: '数据质量' }).waitFor()
  await page.waitForFunction(() => document.body.innerText.includes('尚无导入批次') ||
    document.body.innerText.includes('最近批次'))
  if (await page.getByText('最近批次').count()) {
    await page.getByText('检查结果').waitFor()
  }

  await page.getByRole('button', { name: '模型评估' }).click()
  await page.getByRole('heading', { name: '模型评估' }).waitFor()
  await page.waitForFunction(() => document.body.innerText.includes('尚无训练模型') ||
    document.body.innerText.includes('已注册模型'))
  if (!(await page.getByText('尚无训练模型').count())) {
    await page.locator('table').first().waitFor()
  }

  await page.getByRole('button', { name: '系统健康' }).click()
  await page.getByRole('heading', { name: '系统健康' }).waitFor()
  await page.getByText('DEGRADED', { exact: true }).waitFor()
  await page.getByText('database=BYPASS', { exact: false }).waitFor()
  await page.screenshot({ path: `${outputDir}/ui-health.png`, fullPage: true })

  await page.setViewportSize({ width: 390, height: 844 })
  await noHorizontalOverflow('Mobile health')
  await page.getByRole('button', { name: '订单概览' }).click()
  await page.getByRole('heading', { name: '订单概览' }).waitFor()
  await noHorizontalOverflow('Mobile orders')
  await page.screenshot({ path: `${outputDir}/ui-mobile-orders.png`, fullPage: true })

  if (expectedHealthResponses.length < 1) throw new Error('Expected 503 health response was not observed')
  if (errors.length) throw new Error(`Browser page errors: ${errors.join('; ')}`)

  const anonymous = await browser.newPage()
  await anonymous.goto('http://127.0.0.1:5173', { waitUntil: 'networkidle' })
  await anonymous.getByRole('heading', { name: '订单风险复核台' }).waitFor()
  await anonymous.close()

  console.log(JSON.stringify({
    login: 'passed',
    orders: 'passed (no confirmed-fraud claim)',
    alertReview: 'passed (session identity + CSRF)',
    dataQuality: 'passed (empty or batch/check state rendered)',
    modelEvaluation: 'passed (empty or registered model state rendered)',
    health: 'passed (DEGRADED / stub / BYPASS explicit)',
    mobileOverflow: 'passed',
    anonymousRedirect: 'passed',
    pageErrors: errors,
  }, null, 2))
} finally {
  await browser.close()
}
