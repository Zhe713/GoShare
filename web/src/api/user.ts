import { apiGet, apiPost } from './request'

/** 用户（与后端 UserVO 字段对齐） */
export interface UserInfo {
  id: number
  phone: string
  nickname?: string
  avatarUrl?: string
  realName?: string
  /** 1=已实名 */
  realNameVerified?: number
  /** 1=已认证车主 */
  driverVerified?: number
  role: string
  trustScore?: string
}

/**
 * 他人可见的公开资料（后端 UserProfileVO）。
 * 注意这里没有手机号和真名——后端已把带 PII 的接口收为内部命令。
 */
export interface UserProfile {
  id: number
  nickname?: string
  avatarUrl?: string
  trustScore?: string
  realNameVerified?: boolean
  driverVerified?: boolean
  joinedAt?: string
}

/** 登录响应（后端 LoginResponse 是扁平结构，不是嵌套 user） */
export interface LoginResult {
  token: string
  userId: number
  nickname?: string
  role: string
}

export function login(phone: string, password: string): Promise<LoginResult> {
  return apiPost<LoginResult>('/user/login', { phone, password })
}

/** 当前登录用户（token 由拦截器注入；401 会自动清 token） */
export function currentUser(): Promise<UserInfo> {
  return apiGet<UserInfo>('/user/me')
}

export function register(body: { phone: string; password: string; name?: string }): Promise<{ id: number }> {
  return apiPost<{ id: number }>('/user/register', body)
}

/** 车主认证：提交申请 */
export function certificationApply(body: {
  licensePlate: string
  vehicleModel: string
  seatCount: number
  drivingLicenseNo?: string
}): Promise<{ id: number }> {
  return apiPost<{ id: number }>('/driver/certification/apply', body)
}

/** 车主认证：我的认证记录 */
export interface Certification {
  id: number
  licensePlate: string
  vehicleModel: string
  seatCount: number
  /** 0待审核 1通过 2拒绝 */
  status: number
  auditRemark?: string
}

export function myCertification(): Promise<Certification | null> {
  return apiGet<Certification | null>('/driver/certification/mine')
}

/** 行程账单（F5：月度聚合） */
export interface TripBill {
  month: string
  orderCount: number
  totalDistanceKm: number
  totalPay: number
  taxiBenchmark: number
  savedEstimate: number
  topRoutes: { fromCity: string; toCity: string; count: number }[]
}

export function tripBill(month?: string): Promise<TripBill> {
  return apiGet<TripBill>(`/order/bill${month ? '?month=' + month : ''}`)
}

/** 公开资料：行程详情页展示车主卡片用（脱敏，任何登录用户可查） */
export function getUserProfile(id: number | string): Promise<UserProfile> {
  return apiGet<UserProfile>(`/user/${id}/profile`)
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

/** F1 订单草稿确认卡（对话式下单：口语 → 确认卡，点确认才真正下单） */
export interface OrderDraft {
  tripId: number | null
  fromCity: string | null
  toCity: string | null
  departureTime: string | null
  remainSeats: number | null
  seatCount: number
  unitPrice: number | null
  estimatedTotal: number | null
  cancelPolicy: string | null
  stopCities: string[] | null
  orderable: boolean
  message: string | null
}

export function orderDraft(text: string): Promise<OrderDraft> {
  return apiPost<OrderDraft>('/assistant/order-draft', { text })
}

/** 天气 */
export function weather(city: string): Promise<{ city: string; weather: string; temperature: string; advice: string }> {
  return apiGet(`/notify/weather?city=${encodeURIComponent(city)}`)
}
