import { expect, type Page } from '@playwright/test'

/**
 * E2E 公共夹具
 *
 * 为什么要「补数据」：E2E 里断言「某线路有 2 条班次」这类数量，本质依赖种子数据，
 * 而种子数据的发车时间会随时间流逝变成过去时（首页只展示未来班次），用例就会
 * 因为「数据过期」而不是「代码坏了」失败。所以这里统一改成运行时自愈：
 * 先查该线路现有未来班次，不够就通过真实接口补发车，再按实际数量断言。
 */

export const API = 'http://localhost:8000/api'
export const PASSENGER_PHONE = '13900139000'
export const DRIVER_PHONE = '13800138000'
export const PASSWORD = '123456'

export interface TripLite {
  id: number
  fromCity?: string
  toCity?: string
  departureTime?: string
  price?: number | string
  remainSeats?: number
}

/** 用真实登录接口拿 token（不依赖页面，避免用例被 UI 改动牵连） */
export async function apiLogin(page: Page, phone: string = PASSENGER_PHONE): Promise<string> {
  const resp = await page.request.post(`${API}/user/login`, {
    data: { phone, password: PASSWORD },
  })
  const body = await resp.json()
  expect(body.code, `登录失败：${JSON.stringify(body)}`).toBe(200)
  return body.data.token as string
}

/** 把 token 写进 localStorage，页面加载即视为已登录 */
export async function loginAsPassenger(page: Page): Promise<string> {
  const token = await apiLogin(page)
  await page.addInitScript((t) => localStorage.setItem('goshare-token', t), token)
  return token
}

/** 查询某线路的未来班次（首页/搜索页用的是同一个接口） */
export async function searchTrips(
  page: Page,
  token: string,
  fromCity: string,
  toCity: string,
): Promise<TripLite[]> {
  const url = `${API}/trip/search?fromCity=${encodeURIComponent(fromCity)}&toCity=${encodeURIComponent(toCity)}`
  const resp = await page.request.get(url, { headers: { Authorization: `Bearer ${token}` } })
  const body = await resp.json()
  expect(body.code, `搜索失败：${JSON.stringify(body)}`).toBe(200)
  return (body.data ?? []) as TripLite[]
}

/** 司机发布一条「明天 18:00 之后」的行程，返回 tripId */
export async function publishFutureTrip(
  page: Page,
  fromCity: string,
  toCity: string,
  opts: { totalSeats?: number; price?: number; hourOffset?: number } = {},
): Promise<number> {
  const { totalSeats = 3, price = 45, hourOffset = 0 } = opts
  const dtoken = await apiLogin(page, DRIVER_PHONE)
  const t = new Date()
  t.setDate(t.getDate() + 1)
  t.setHours(18, 0, 0, 0)
  t.setMinutes(t.getMinutes() + hourOffset * 30)

  const resp = await page.request.post(`${API}/trip/publish`, {
    headers: { Authorization: `Bearer ${dtoken}` },
    // 后端按 LocalDateTime 解析，必须去掉末尾的 Z（否则会当 UTC 再转一次）
    data: {
      fromCity,
      toCity,
      departureTime: t.toISOString().slice(0, 19),
      totalSeats,
      price,
      remark: 'E2E 自愈补数据',
    },
  })
  const body = await resp.json()
  expect(body.code, `发布行程失败：${JSON.stringify(body)}`).toBe(200)
  return body.data.id as number
}

/**
 * 保证某线路至少有 want 条未来班次，返回补完后该线路的实际班次数量。
 * 用例断言用返回值而不是写死数字，这样数据多少都不会误报。
 */
export async function ensureFutureTrips(
  page: Page,
  fromCity: string,
  toCity: string,
  want = 2,
): Promise<number> {
  const token = await apiLogin(page)
  let list = await searchTrips(page, token, fromCity, toCity)
  for (let i = list.length; i < want; i++) {
    await publishFutureTrip(page, fromCity, toCity, { hourOffset: i })
  }
  // ES 双写有秒级可见延迟，轮询到数量达标（最多 ~10s）
  for (let i = 0; i < 10 && list.length < want; i++) {
    await page.waitForTimeout(1000)
    list = await searchTrips(page, token, fromCity, toCity)
  }
  expect(list.length, `${fromCity}→${toCity} 未来班次不足 ${want} 条`).toBeGreaterThanOrEqual(want)
  return list.length
}
