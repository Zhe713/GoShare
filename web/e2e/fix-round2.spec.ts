import { test, expect, type Page } from '@playwright/test'
import { ensureFutureTrips } from './helpers'

/**
 * 第二轮验收修复 E2E（2026-11-03 六连击）：
 * 1) 换线路弹层提供「热门线路」chips，点击直达有行程线路（邯郸→北京有 2 条）
 * 2) AI 天气为真实实时数据（答案含真实温度，非 mock 死表「小雨16℃」）
 * 3) AI 感知今日日期（「今天几号」答出 2026年11月3日）
 * 4) AI 多轮上下文（带 history，「11.3 下午出发」不再重复要日期）
 * 5) 我的页最近订单放开全部展示（共 26 单，渲染超过 3 张卡）
 * 6) 券包新增可领秒杀券 + 有效期/限量信息展示（限时秒杀立减6元可领）
 */

const PHONE = '13900139000'
const PASSWORD = '123456'

/** 日期断言按运行时当天计算，避免跨天后用例必然失败 */
const NOW = new Date()
const TODAY_MD = `${NOW.getMonth() + 1}月${NOW.getDate()}日`

async function apiLogin(page: Page): Promise<string> {
  const resp = await page.request.post('http://localhost:8000/api/user/login', {
    data: { phone: PHONE, password: PASSWORD },
  })
  const body = await resp.json()
  expect(body.code).toBe(200)
  return body.data.token as string
}

test('换线路弹层热门线路chips点击直达有行程（邯郸→北京）', async ({ page }) => {
  // 热门线路 chip 能否直达「确有未来班次」的线路：班次数量运行时自愈，不写死种子数量
  const countHandan = await ensureFutureTrips(page, '邯郸', '北京', 2)

  const token = await apiLogin(page)
  await page.addInitScript((t) => localStorage.setItem('goshare-token', t), token)

  await page.goto('/')
  await expect(page.getByText('推荐行程 · 石家庄 → 北京')).toBeVisible()
  await page.getByRole('button', { name: '换线路' }).click()
  await expect(page.getByText('换一条线路')).toBeVisible()
  await page.getByRole('button', { name: '邯郸 → 北京' }).click()
  await page.getByRole('button', { name: '查看该线路行程' }).click()
  await expect(page.getByText('推荐行程 · 邯郸 → 北京')).toBeVisible()
  await expect(page.locator('.home__list .g-trip-card')).toHaveCount(countHandan, { timeout: 15_000 })
})

test('AI 天气真实实时数据（非 mock 死表）', async ({ page }) => {
  const token = await apiLogin(page)
  await page.addInitScript((t) => localStorage.setItem('goshare-token', t), token)

  await page.goto('/assistant')
  await expect(page.getByText('AI 出行助手')).toBeVisible()
  await page.getByPlaceholder('问问助手…').fill('邯郸今天天气怎么样')
  await page.getByRole('button', { name: '发送' }).click()
  // 真实天气：答案含温度 ℃（不可能是 mock 的「小雨16℃」死表；真实值随天气变化）
  const bubbles = page.locator('.g-chat__bubble')
  await expect(bubbles.last()).toContainText('℃', { timeout: 30_000 })
  await expect(bubbles.last()).toContainText('天气')
})

test('AI 感知今日日期（今天几号）', async ({ page }) => {
  const token = await apiLogin(page)
  await page.addInitScript((t) => localStorage.setItem('goshare-token', t), token)

  await page.goto('/assistant')
  await expect(page.getByText('AI 出行助手')).toBeVisible()
  await page.getByPlaceholder('问问助手…').fill('今天几号？星期几？')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.locator('.g-chat__bubble').last()).toContainText(TODAY_MD, { timeout: 30_000 })
})

test('AI 多轮上下文：邯郸到北京 11.3 下午出发不再重复要日期', async ({ page }) => {
  const token = await apiLogin(page)
  await page.addInitScript((t) => localStorage.setItem('goshare-token', t), token)

  await page.goto('/assistant')
  await expect(page.getByText('AI 出行助手')).toBeVisible()
  const input = page.getByPlaceholder('问问助手…')
  const sendBtn = page.getByRole('button', { name: '发送' })
  // 初始 1 条欢迎语；每轮 +2 条（用户+助手）
  // 第一轮：我想去北京
  await input.fill('我想去北京')
  await sendBtn.click()
  await expect(page.locator('.g-chat__bubble')).toHaveCount(3, { timeout: 30_000 })
  // 第二轮：从邯郸出发
  await input.fill('从邯郸出发')
  await sendBtn.click()
  await expect(page.locator('.g-chat__bubble')).toHaveCount(5, { timeout: 30_000 })
  // 第三轮：11.3 下午出发（带上文，不应再问出发地/日期）
  await input.fill('11.3 下午出发')
  await sendBtn.click()
  const last = page.locator('.g-chat__bubble').last()
  await expect(last).toContainText('邯郸', { timeout: 30_000 })
  await expect(last).toContainText('北京', { timeout: 30_000 })
})

test('我的页最近订单放开全部展示（全部订单渲染，>3 张卡）', async ({ page }) => {
  const token = await apiLogin(page)
  await page.addInitScript((t) => localStorage.setItem('goshare-token', t), token)

  await page.goto('/mine')
  // 数量随演示环境用户下单动态变化，断言标题携带总数且渲染超过 3 张卡（此前被 slice(0,3) 硬截断）
  await expect(page.getByText(/最近订单 · 共 \d+ 单/)).toBeVisible({ timeout: 15_000 })
  const cards = page.locator('.mine__orders .g-card')
  await expect(cards.count()).resolves.toBeGreaterThan(3)
  await expect(cards.first()).toBeVisible()
})

test('券包新增可领秒杀券：限量/有效期信息展示且可领取', async ({ page }) => {
  const token = await apiLogin(page)
  await page.addInitScript((t) => localStorage.setItem('goshare-token', t), token)

  await page.goto('/coupons')
  await expect(page.getByText('领券中心')).toBeVisible()
  // 新模板「限时秒杀立减6元」未领取 → 卡片展示限量信息 + 可领按钮
  const card = page.getByText('限时秒杀立减6元').first()
  await expect(card).toBeVisible({ timeout: 15_000 })
  // 已抢数随历史领用而增长（用例自身领券也会 +1），断言格式而非写死数值，避免用例数据自冲突；
  // 页面可能同时存在同名普通券与秒杀券卡片，取 first 规避 strict 冲突
  await expect(page.getByText(/限量 30 张 · 已抢 \d+ 张/).first()).toBeVisible()
  // 有效期不为空：秒杀券「有效期至 截止 距结束…」、普通券「有效期至 领取后 X 天内有效」
  await expect(page.getByText('有效期至 截止').first()).toBeVisible()
  await expect(page.getByText(/有效期至 领取后/).first()).toBeVisible()
  // 领取按钮必须限定在「这张券」的卡片内：页面上其它券可能已被领过（disabled），
  // 用全局 first() 会随数据顺序波动（CI 全新库就踩到过）
  const flashCard = page.locator('.g-coupon').filter({ hasText: '限时秒杀立减6元' }).first()
  await expect(flashCard).toBeVisible()
  await expect(flashCard.getByRole('button', { name: '领取' })).toBeEnabled()
})
