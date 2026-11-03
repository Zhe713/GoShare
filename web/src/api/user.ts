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

/** 券模板（领券中心数据源，后端 coupon_template 表） */
export interface CouponTemplate {
  id: number
  templateName: string
  type: string
  threshold: number
  discountValue: number
  totalCount: number
  receivedCount: number
  startTime: string
  endTime: string
  expireDays: number
  status: number
}

export function couponTemplates(): Promise<CouponTemplate[]> {
  return apiGet<CouponTemplate[]>('/coupon/template/list')
}

/** AI 助手通用问答（走省 token 四关卡；history 为多轮上下文 [{role, content}]，可为空） */
export function assistantChat(
  text: string,
  history?: { role: string; content: string }[],
): Promise<{
  answer: string
  fromCache: boolean
  modelName: string
}> {
  return apiPost<{ answer: string; fromCache: boolean; modelName: string }>('/assistant/chat', { text, history })
}

/** 天气 */
export function weather(city: string): Promise<{ city: string; weather: string; temperature: string; advice: string }> {
  return apiGet(`/notify/weather?city=${encodeURIComponent(city)}`)
}
