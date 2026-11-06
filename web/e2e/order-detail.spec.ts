import { test, expect, type Page } from '@playwright/test'
import { API, apiLogin, loginAsPassenger, publishFutureTrip } from './helpers'

/**
 * 订单详情页 E2E（`/order/:orderNo`）——回归三件事：
 * 1) 路由真的可达（此前该路由无任何入口，属死代码）
 * 2) 金额单位正确（payPrice 是「元」，曾被 fenToYuan 除以 100 显示成 0.45）
 * 3) A2 取消挽留真的有 UI：点取消先展示费用明细/替代行程/话术，二次确认才真取消
 */

const PRICE = 45

/** 直接走 API 下单（下一单待支付订单），返回 orderNo ——比 UI 点击更稳，且不依赖券包状态 */
async function createPendingOrder(page: Page, token: string, tripId: number): Promise<string> {
  const resp = await page.request.post(`${API}/order/create`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      tripId,
      seatCount: 1,
      // 幂等键上限 64；用时间戳 + 随机串保证多次运行不撞
      idempotentKey: `e2e-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    },
  })
  const body = await resp.json()
  expect(body.code).toBe(200)
  expect(body.data.status).toBe('CREATED')
  return body.data.orderNo as string
}

test('订单详情：金额按元展示 + A2 取消挽留先说明再取消', async ({ page }) => {
  const tripId = await publishFutureTrip(page, '石家庄', '邯郸', { totalSeats: 2, price: PRICE })
  const token = await loginAsPassenger(page)
  const orderNo = await createPendingOrder(page, token, tripId)

  // 1) 路由可达 + 状态/金额正确
  await page.goto(`/order/${orderNo}`)
  await expect(page.getByText('订单详情')).toBeVisible({ timeout: 15_000 })
  await expect(page.locator('.order__status')).toHaveText('待支付')
  await expect(page.locator('.order__no')).toHaveText(orderNo)

  // 金额必须是「元」原值两位小数（回归：曾显示成价格/100）
  await expect(page.locator('.order__money')).toHaveText(`${PRICE.toFixed(2)} 元`)

  // 2) 待支付时有「去支付」入口
  await expect(page.getByRole('button', { name: '去支付' })).toBeVisible()

  // 3) 点取消 → 先出 A2 挽留抽屉（不是直接取消）
  await page.getByRole('button', { name: '取消订单（先看费用说明）' }).click()
  await expect(page.getByText('取消前先看一下')).toBeVisible({ timeout: 15_000 })
  // 精确匹配：抽屉标题「费用说明」，避免和按钮文案「取消订单（先看费用说明）」撞上
  await expect(page.getByText('费用说明', { exact: true })).toBeVisible()
  await expect(page.getByText('预计退款', { exact: true })).toBeVisible()
  await expect(page.getByText('取消责任', { exact: true })).toBeVisible()

  // 4) 「我再想想」→ 抽屉关闭，订单状态未变（挽留生效，没有误取消）
  await page.getByRole('button', { name: '我再想想' }).click()
  await expect(page.getByText('取消前先看一下')).toBeHidden()
  await expect(page.locator('.order__status')).toHaveText('待支付')

  // 5) 再次进入 → 确认「仍要取消」→ 状态变已取消
  await page.getByRole('button', { name: '取消订单（先看费用说明）' }).click()
  await expect(page.getByRole('button', { name: '仍要取消' })).toBeVisible({ timeout: 15_000 })
  await page.getByRole('button', { name: '仍要取消' }).click()
  await expect(page.locator('.order__status')).toHaveText('已取消', { timeout: 15_000 })

  // 6) 非待支付订单从「我的」点进去走详情页（此前一律跳收银台，收银台会报不可支付）
  await page.goto('/mine')
  const card = page.locator('.mine__order').filter({ hasText: orderNo })
  await expect(card).toBeVisible({ timeout: 15_000 })
  await card.click()
  await expect(page).toHaveURL(new RegExp(`/order/${orderNo}$`))
  await expect(page.locator('.order__status')).toHaveText('已取消')
})

test('订单详情：待支付订单点「去支付」进收银台且倒计时文案不重复', async ({ page }) => {
  const tripId = await publishFutureTrip(page, '石家庄', '邯郸', { totalSeats: 2, price: PRICE })
  const token = await loginAsPassenger(page)
  const orderNo = await createPendingOrder(page, token, tripId)

  await page.goto(`/order/${orderNo}`)
  await page.getByRole('button', { name: '去支付' }).click()

  await expect(page.getByText('待支付金额')).toBeVisible({ timeout: 15_000 })
  // 回归：countdownText 自带「还剩」，模板又拼一次会出现「还剩 还剩」
  const countdown = page.locator('.pay__countdown')
  await expect(countdown).toContainText('还剩')
  await expect(countdown).not.toContainText('还剩 还剩')
})
