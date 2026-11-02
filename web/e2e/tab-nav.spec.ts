import { test, expect } from '@playwright/test'

test('TabBar 导航 + 登录：我的 → 登录 → 各 Tab 切换', async ({ page }) => {
  await page.goto('/')
  // 1) 点「我的」进入 /mine（TabBar 导航修复验证）
  await page.getByRole('button', { name: '我的' }).click()
  await expect(page).toHaveURL(/\/mine/, { timeout: 15000 })
  // 2) 未登录自动弹登录抽屉（预填演示账号）→ 点「登录」
  await expect(page.getByText('登录 GoShare')).toBeVisible()
  await page.getByRole('button', { name: '登录', exact: true }).last().click()
  await expect(page.getByText('最近订单')).toBeVisible({ timeout: 15000 })
  // 3) 登录成功 → 切券包 / 搜索 / 首页
  await page.getByRole('button', { name: '券包' }).click()
  await expect(page).toHaveURL(/\/coupons/, { timeout: 15000 })
  await page.getByRole('button', { name: '搜索' }).click()
  await expect(page).toHaveURL(/\/search/, { timeout: 15000 })
  await page.getByRole('button', { name: '首页' }).click()
  await expect(page).toHaveURL(/\/$/, { timeout: 15000 })
})
