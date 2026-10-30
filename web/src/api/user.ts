import { apiGet, apiPost } from './request'

/** 用户 */
export interface UserInfo {
  id: number
  phone: string
  name?: string
  role: string
  trustScore?: string
}

export function login(phone: string, password: string): Promise<{ token: string; user: UserInfo }> {
  return apiPost<{ token: string; user: UserInfo }>('/user/login', { phone, password })
}

export function register(body: { phone: string; password: string; name?: string }): Promise<{ id: number }> {
  return apiPost<{ id: number }>('/user/register', body)
}

export function getUser(id: number | string): Promise<UserInfo> {
  return apiGet<UserInfo>(`/user/${id}`)
}

/** 优惠券（后端 CouponVO 字段） */
export interface Coupon {
  id: number
  couponNo: string
  couponTemplateId: number
  type: string
  threshold: number
  discountValue: number
  /** 0=已领未用 1=已使用 */
  status: number
  orderNo: string | null
  expireAt: string | null
}

export function myCoupons(): Promise<Coupon[]> {
  return apiGet<Coupon[]>('/coupon/mine')
}

export function claimCoupon(templateId: number): Promise<{ id: number }> {
  return apiPost<{ id: number }>('/coupon/receive', { templateId })
}

/** 领券中心模板（后端无列表接口，静态取真实初始化模板：id/名称/门槛/面值） */
export const COUPON_TEMPLATES: { id: number; name: string; amount: number; minSpend: number; note: string }[] = [
  { id: 1, name: '新客满50减10', amount: 10, minSpend: 50, note: '新客专享' },
  { id: 2, name: '拼车8.5折券', amount: 0, minSpend: 0, note: '下单自动打折' },
  { id: 3, name: '限时秒杀立减5元', amount: 5, minSpend: 0, note: '已领完' },
]

/** AI 助手通用问答（走省 token 四关卡） */
export function assistantChat(text: string): Promise<{
  answer: string
  fromCache: boolean
  modelName: string
}> {
  return apiPost<{ answer: string; fromCache: boolean; modelName: string }>('/assistant/chat', { text })
}

/** 天气 */
export function weather(city: string): Promise<{ city: string; weather: string; temperature: string; advice: string }> {
  return apiGet(`/notify/weather?city=${encodeURIComponent(city)}`)
}
