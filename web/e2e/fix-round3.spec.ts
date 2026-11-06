import { test, expect, type Page } from '@playwright/test'

/**
 * 第三轮验收修复 E2E（2026-11-03 五截图「AI 回答出错」）：
 * 1) 行程幻觉修复：北京→邯郸库内无班次 → AI 答「暂无班次」（绝不编造"多个班次可选"）
 * 2) 天气上下文+日期：先问邯郸天气再问「11.4号的天气」→ 答邯郸11月4日真实预报（不再回退石家庄今日）
 * 3) 实时时间：现在几点了 → 返回服务器当前时刻（不再答"不显示实时时间"）
 */

const PHONE = '13900139000'
const PASSWORD = '123456'

/**
 * 日期相关断言必须按「运行时当天」计算，不能写死某一天——
 * 否则跨天后用例必挂（原断言硬编码 2026-11-03，11-04 起三条用例全红）。
 */
const NOW = new Date()
const TODAY_TEXT = `${NOW.getFullYear()}年${NOW.getMonth() + 1}月${NOW.getDate()}日`
const TODAY_MD = `${NOW.getMonth() + 1}月${NOW.getDate()}日`

/**
 * 天气用例问「明天」而不是「今天」：问今天就等于问当前天气，
 * 回答会写「今日天气」而不含具体日期串，断言会假失败。
 */
const TOMORROW = new Date(NOW.getTime() + 24 * 60 * 60 * 1000)
const TOMORROW_MD = `${TOMORROW.getMonth() + 1}月${TOMORROW.getDate()}日`
const TOMORROW_DOT = `${TOMORROW.getMonth() + 1}.${TOMORROW.getDate()}号`

async function apiLogin(page: Page): Promise<string> {
  const resp = await page.request.post('http://localhost:8000/api/user/login', {
    data: { phone: PHONE, password: PASSWORD },
  })
  const body = await resp.json()
  expect(body.code).toBe(200)
  return body.data.token as string
}

async function openAssistant(page: Page) {
  const token = await apiLogin(page)
  await page.addInitScript((t) => localStorage.setItem('goshare-token', t), token)
  await page.goto('/assistant')
  await expect(page.getByText('AI 出行助手')).toBeVisible()
}

async function ask(page: Page, question: string) {
  await page.getByPlaceholder('问问助手…').fill(question)
  await page.getByRole('button', { name: '发送' }).click()
}

test('行程幻觉修复：北京→邯郸无班次答「暂无班次」不编造', async ({ page }) => {
  await openAssistant(page)
  await ask(page, '北京到邯郸有车吗')
  const last = page.locator('.g-chat__bubble').last()
  await expect(last).toContainText('暂无班次', { timeout: 30_000 })
  await expect(last).toContainText('北京')
  await expect(last).toContainText('邯郸')
  // 不再出现幻觉句式
  await expect(last).not.toContainText('多个班次可选')
  await expect(last).not.toContainText('还有多个可选班次')
})

test(`天气上下文+日期解析：${TOMORROW_DOT}的天气答邯郸${TOMORROW_MD}（非石家庄今日）`, async ({ page }) => {
  await openAssistant(page)
  await ask(page, '邯郸今天天气怎么样')
  await expect(page.locator('.g-chat__bubble').last()).toContainText('℃', { timeout: 30_000 })
  // 追问日期天气：城市应从上下文回捞为邯郸，日期应解析为明天
  await ask(page, `${TOMORROW_DOT}的天气`)
  const last = page.locator('.g-chat__bubble').last()
  await expect(last).toContainText('邯郸', { timeout: 30_000 })
  await expect(last).toContainText(TOMORROW_MD)
  await expect(last).toContainText('℃')
  await expect(last).not.toContainText('石家庄')
})

test('实时时间：现在几点了返回服务器当前时刻', async ({ page }) => {
  await openAssistant(page)
  await ask(page, '现在几点了')
  const last = page.locator('.g-chat__bubble').last()
  await expect(last).toContainText(new RegExp(`现在是${TODAY_TEXT} \\d{2}:\\d{2}`), { timeout: 30_000 })
})
