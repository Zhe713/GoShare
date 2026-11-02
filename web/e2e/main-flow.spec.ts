import { test, expect, type Page } from '@playwright/test'

/**
 * GoShare H5 主链路 E2E（阶段 7）：
 * 1) 乘客登录（13900139000 / 123456）
 * 2) A1 语义搜索（口语 → 结构化回显 → 智能排序行程）
 * 3) 行程详情下单占座 → 收银台余额支付
 * 4) 我的订单出现 PAID 状态
 * 5) AI 助手通用问答（省 token 四关卡：规则/缓存标注可见）
 */

const PHONE = '13900139000'
const PASSWORD = '123456'
const DRIVER_PHONE = '13800138000'

async function apiLogin(page: Page, phone: string, password: string): Promise<string> {
  const resp = await page.request.post('http://localhost:8000/api/user/login', {
    data: { phone, password },
  })
  const body = await resp.json()
  expect(body.code).toBe(200)
  return body.data.token as string
}

async function login(page: Page): Promise<string> {
  // API 预置 token（UI 登录抽屉流程见 mine__login 表单，E2E 主链路以稳定为先）
  const token = await apiLogin(page, PHONE, PASSWORD)
  await page.addInitScript((t) => localStorage.setItem('goshare-token', t), token)
  // 我的页：登录态用户卡片出现（13900139000）+ 最近订单加载
  await page.goto('/mine')
  await expect(page.getByText(PHONE)).toBeVisible({ timeout: 15_000 })
  return token
}

/** 司机发布一个「明天出发」的未来行程，返回 tripId（E2E 数据自愈，避免历史行程已出发无法下单） */
async function publishFutureTrip(page: Page): Promise<number> {
  const dtoken = await apiLogin(page, DRIVER_PHONE, PASSWORD)
  const t = new Date()
  t.setDate(t.getDate() + 1)
  t.setHours(18, 0, 0, 0)
  const iso = t.toISOString().slice(0, 19) // ISO 格式（Jackson LocalDateTime 默认解析）
  const resp = await page.request.post('http://localhost:8000/api/trip/publish', {
    headers: { Authorization: `Bearer ${dtoken}` },
    data: {
      fromCity: '石家庄',
      toCity: '邯郸',
      departureTime: iso,
      totalSeats: 2,
      price: 45,
      remark: '可带大件行李',
    },
  })
  const body = await resp.json()
  expect(body.code).toBe(200)
  return body.data.id as number
}

test('主链路：登录 → 语义搜索 → 下单 → 支付 → 订单 PAID', async ({ page }) => {
  const newTripId = await publishFutureTrip(page)
  await login(page)

  // A1 语义搜索：口语 → 结构化回显 + 智能排序（结果应包含刚发布的未来行程）
  await page.goto('/search')
  await page.getByPlaceholder(/试试/).fill('从石家庄去邯郸')
  await page.getByRole('button', { name: '智能搜索' }).click()
  await expect(page.getByText('我理解的你的需求')).toBeVisible({ timeout: 15_000 })
  const tripCard = page.locator('.g-trip-card').first()
  await expect(tripCard).toBeVisible()
  // 行程卡片含路线
  await expect(tripCard.getByText('石家庄')).toBeVisible()
  await expect(tripCard.getByText('邯郸')).toBeVisible()

  // 进入新发布行程详情 → 下单（未来出发，可订）
  await page.goto(`/trip/${newTripId}`)
  await expect(page.getByText('行程详情')).toBeVisible()
  await expect(page.getByText('立即下单占座')).toBeVisible()
  await page.getByRole('button', { name: '立即下单占座' }).click()

  // 收银台
  await expect(page.getByText('待支付金额')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByText('订单号')).toBeVisible()
  const orderNoText = await page.locator('.pay__no').innerText()
  expect(orderNoText).toMatch(/\d{20}/)

  // 余额模拟支付
  await page.getByRole('button', { name: '立即支付' }).click()
  await expect(page.locator('.pay__done')).toBeVisible({ timeout: 15_000 })

  // 我的订单出现 PAID
  await page.goto('/mine')
  await expect(page.locator('.mine__order-status--PAID').first()).toBeVisible({ timeout: 15_000 })
})

test('AI 助手：规则关卡先答 + 缓存命中标注', async ({ page }) => {
  await login(page)

  await page.goto('/assistant')
  await expect(page.getByText('AI 出行助手')).toBeVisible()

  // 第一问：FAQ 命中省 token 关卡（规则模板先答 或 已缓存命中，均零模型调用）
  await page.getByPlaceholder('问问助手…').fill('取消订单会扣钱吗')
  await page.getByRole('button', { name: '发送' }).click()
  // 助手气泡出现回复
  await expect(page.locator('.g-chat--assistant .g-chat__bubble').last()).toBeVisible({ timeout: 20_000 })
  const meta = page.locator('.assistant__meta').last()
  await expect(meta).toBeVisible({ timeout: 20_000 })
  await expect(meta).toContainText(/规则|缓存命中/)

  // 第二问：同一问题缓存命中（关卡1，零模型调用）
  await page.getByPlaceholder('问问助手…').fill('取消订单会扣钱吗')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.locator('.assistant__meta').last()).toContainText('缓存命中', { timeout: 20_000 })
})
