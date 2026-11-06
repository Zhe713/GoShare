import { test, expect } from '@playwright/test'
import { ensureFutureTrips, loginAsPassenger } from './helpers'

/**
 * 验收修复 E2E（阶段 7 收口后补）：
 * 1) 首页行程可自更新：换线路弹层 → 保定→北京 → 列表与标题刷新
 * 2) 领券中心已领券动态置灰（id3 已领 → 显示「已领取」且按钮禁用）
 *
 * 注意：线路班次数量改为「运行时自愈 + 按实际数量断言」，不再依赖种子数据的发车时间。
 */

test('首页换线路刷新行程：石家庄→北京 → 保定→北京', async ({ page }) => {
  // 先把两条线路的未来班次补够（种子数据会随时间过期）
  const countShijiazhuang = await ensureFutureTrips(page, '石家庄', '北京', 2)
  const countBaoding = await ensureFutureTrips(page, '保定', '北京', 2)

  await loginAsPassenger(page)

  await page.goto('/')
  // 默认推荐石家庄→北京
  await expect(page.getByText('推荐行程 · 石家庄 → 北京')).toBeVisible()
  await expect(page.locator('.home__list .g-trip-card')).toHaveCount(countShijiazhuang, { timeout: 15_000 })

  // 换线路：保定 → 北京
  await page.getByRole('button', { name: '换线路' }).click()
  await expect(page.getByText('换一条线路')).toBeVisible()
  await page.getByLabel('出发城市').fill('保定')
  await page.getByLabel('到达城市').fill('北京')
  await page.getByRole('button', { name: '查看该线路行程' }).click()
  // 标题随线路更新，列表刷新为该线路班次
  await expect(page.getByText('推荐行程 · 保定 → 北京')).toBeVisible()
  await expect(page.locator('.home__list .g-trip-card')).toHaveCount(countBaoding, { timeout: 15_000 })
})

test('领券中心已领券动态置灰（id3 已领取 → 不可再点）', async ({ page }) => {
  await loginAsPassenger(page)

  await page.goto('/coupons')
  await expect(page.getByText('领券中心')).toBeVisible()
  // 用户已领立减5元券（templateId=3）→ 卡片按钮显示「已领取」且禁用
  await expect(page.getByText('已领取，每人限领一张').first()).toBeVisible({ timeout: 15_000 })
  const claimedBtn = page.getByRole('button', { name: '已领取' }).first()
  await expect(claimedBtn).toBeDisabled()
})
