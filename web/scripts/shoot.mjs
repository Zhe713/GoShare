/**
 * README 截图生成器（可复现）
 *
 * 前置：中间件 + 8 个服务 + 前端 dev server 已启动（http://localhost:5173）
 * 用法：cd web && node ../scripts/shoot.mjs
 * 产物：docs/images/*.png（H5 页面 390x844 @2x，大屏 1440x900）
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const OUT = resolve(__dirname, '../../docs/images')
const WEB = 'http://localhost:5173'
const API = 'http://localhost:8000'

mkdirSync(OUT, { recursive: true })

const P = { phone: '13700137000', password: '123456' } // 乘客
const D = { phone: '13900139000', password: '123456' } // 车主

async function api(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  return res.json()
}

const login = async (u) => (await api('/api/user/login', { method: 'POST', body: u })).data?.token

async function shoot(page, path, name, opts = {}) {
  await page.goto(WEB + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)
  if (opts.before) await opts.before(page)
  await page.screenshot({ path: resolve(OUT, `${name}.png`), fullPage: opts.fullPage ?? false })
  console.log(`  ✓ ${name}.png  ${path}`)
}

const main = async () => {
  const pToken = await login(P)
  const dToken = await login(D)
  if (!pToken) throw new Error('乘客登录失败，检查服务是否就绪')

  // 自建一条演示行程（6 座 / 3 站 / 2 区间），避免依赖种子数据、也避免截图自消耗把展示行程买空
  const date = new Date(Date.now() + 2 * 86400_000)
  const ymd = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
  const published = await api('/api/trip/publish', {
    method: 'POST',
    token: dToken,
    body: {
      fromCity: '石家庄',
      toCity: '邯郸',
      departureTime: `${ymd}T08:00:00`,
      totalSeats: 6,
      price: 40,
      pricePerKm: 0.32,
      stops: [
        { city: '石家庄', address: '石家庄站西广场', offsetMinutes: 0 },
        { city: '邢台', address: '邢台东站', offsetMinutes: 55 },
        { city: '邯郸', address: '邯郸东站', offsetMinutes: 105 },
      ],
      vehiclePlate: '冀A·8866Z',
      vehicleModel: '比亚迪汉 EV',
      vehicleColor: '曜石黑',
      remark: '演示行程',
    },
  })
  const tripId = published.data?.id
  if (!tripId) throw new Error('演示行程发布失败：' + JSON.stringify(published).slice(0, 200))
  console.log(`演示行程已创建 tripId=${tripId}（6 座 / 3 站）`)

  const browser = await chromium.launch()
  const h5 = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
  })
  const page = await h5.newPage()
  await page.addInitScript((t) => localStorage.setItem('goshare-token', t), pToken)

  console.log('H5 截图：')
  await shoot(page, '/', 'home')
  await shoot(page, `/trip/${tripId}`, 'trip-detail')

  // AI 对话式下单确认卡
  await shoot(page, '/assistant', 'ai-order-card', {
    before: async (p) => {
      const input = p.getByPlaceholder(/问问助手|订/)
      await input.fill('从石家庄订后天去邯郸的，两个人')
      await p.getByRole('button', { name: '发送' }).click()
      await p.getByText('确认下单', { exact: false }).first().waitFor({ timeout: 20000 }).catch(() => {})
      await p.waitForTimeout(800)
    },
  })

  // 订单详情 + 取消挽留/改签抽屉：先造一单已支付订单
  try {
    const created = await api('/api/order/create', {
      method: 'POST',
      token: pToken,
      body: { tripId, seatCount: 1, idempotentKey: `shot-${Date.now()}` },
    })
    const orderNo = created.data?.orderNo
    if (orderNo) {
      await api('/api/payment/pay', { method: 'POST', token: pToken, body: { orderNo } })
      await shoot(page, `/order/${orderNo}`, 'order-detail')
      await shoot(page, `/order/${orderNo}`, 'order-reschedule', {
        before: async (p) => {
          await p.getByRole('button', { name: /取消订单/ }).click()
          await p.getByText('取消前先看一下').waitFor({ timeout: 15000 }).catch(() => {})
          await p.waitForTimeout(800)
          // 抽屉内容可滚动：滚到底部，让「改签至此班」按钮进入视口
          await p.evaluate(() => {
            const scrollables = Array.from(document.querySelectorAll('div')).filter(
              (el) => el.scrollHeight > el.clientHeight + 30 && el.textContent?.includes('费用说明'),
            )
            const sheet = scrollables.pop()
            if (sheet) sheet.scrollTop = sheet.scrollHeight
          })
          await p.waitForTimeout(600)
        },
      })
    }
  } catch (e) {
    console.warn('  订单页截图跳过：', e.message)
  }

  await shoot(page, '/mine', 'mine-bill')
  await shoot(page, '/support', 'support')
  await shoot(page, '/certification', 'certification')

  // 车主端
  const dctx = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
  })
  const dpage = await dctx.newPage()
  await dpage.addInitScript((t) => localStorage.setItem('goshare-token', t), dToken)
  await shoot(dpage, '/publish', 'publish-trip')
  await shoot(dpage, '/driver/trips', 'driver-trips')

  // 运营大屏（桌面视口）
  const desk = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 })
  const spage = await desk.newPage()
  await shoot(spage, '/screen.html', 'screen')

  await browser.close()
  console.log('\n完成，产物目录：docs/images/')
}

main().catch((e) => {
  console.error('截图失败：', e.message)
  process.exit(1)
})
