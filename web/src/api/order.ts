import { apiGet, apiPost } from './request'

/** 订单 */
export interface Order {
  orderNo: string
  tripId: number
  passengerId: number
  seatCount: number
  /** 区间站点（阶段1新增） */
  fromStopSeq?: number
  toStopSeq?: number
  distanceKm?: number
  totalPrice: number
  couponDiscount: number
  payPrice: number
  status: string
  paymentNo: string | null
  cancelReason: string | null
  /** 退款单号（已支付订单取消后产生） */
  refundNo?: string
  refundAmount?: number
  /** 订单创建时间（支付倒计时起点：创建后 15 分钟内支付） */
  createdAt: string
  /** 行程摘要（我的订单/收银台展示，来自行程服务） */
  tripFromCity?: string
  tripToCity?: string
  tripDepartureTime?: string
}

export function createOrder(body: {
  tripId: number
  seatCount: number
  couponId?: number
  idempotentKey: string
  fromStopSeq?: number
  toStopSeq?: number
}): Promise<Order> {
  return apiPost<Order>('/order/create', body)
}

export function getOrder(orderNo: string): Promise<Order> {
  return apiGet<Order>(`/order/${orderNo}`)
}

export function myOrders(): Promise<Order[]> {
  return apiGet<Order[]>('/order/mine')
}

export function cancelOrder(orderNo: string, reason?: string): Promise<Order> {
  return apiPost<Order>('/order/cancel', { orderNo, reason })
}

/** A2 取消挽留预览（费用解释 + 替代方案 + 话术）；字段对齐后端 CancelRetentionVO */
export interface CancelRetention {
  alreadyRetained: boolean
  fee: {
    orderNo: string
    status: string
    totalPrice: string
    couponDiscount: string
    payPrice: string
    liability: string
    penalty: string
    refundAmount: string
    feeExplain: string
    ruleExplain: string
  } | null
  alternatives: {
    tripId: number
    fromCity: string
    toCity: string
    departureTime: string
    price: string
    remainSeats: number
  }[]
  pitch: string
}

export function cancelPreview(orderNo: string): Promise<CancelRetention> {
  return apiPost<CancelRetention>('/assistant/cancel-preview', { orderNo })
}

/** 支付 */
export function payOrder(orderNo: string, payType = 'BALANCE'): Promise<{ paymentNo: string; status: string }> {
  return apiPost<{ paymentNo: string; status: string }>('/payment/pay', { orderNo, payType })
}

/** 车主视角：某行程的乘客名单（阶段1新增） */
export function ordersByTrip(tripId: number): Promise<Order[]> {
  return apiGet<Order[]>(`/order/by-trip/${tripId}`)
}

/** 车主确认乘客上车（阶段1新增） */
export function boardPassenger(tripId: number, orderNo: string): Promise<Order> {
  return apiPost<Order>(`/order/board?tripId=${tripId}&orderNo=${orderNo}`, {})
}

/** 车主确认到达（阶段1新增） */
export function completePassenger(tripId: number, orderNo: string): Promise<Order> {
  return apiPost<Order>(`/order/complete?tripId=${tripId}&orderNo=${orderNo}`, {})
}

/** 乘客评价（阶段1新增） */
export function reviewOrder(orderNo: string, rating: number, comment?: string): Promise<Order> {
  return apiPost<Order>('/order/review', { orderNo, rating, comment })
}

/** 改签（F4：PAID 订单改签到同线路新班次，改签免费、差额自动退） */
export function rescheduleOrder(
  orderNo: string,
  newTripId: number,
  fromStopSeq?: number,
  toStopSeq?: number,
): Promise<Order> {
  return apiPost<Order>('/order/reschedule', { orderNo, newTripId, fromStopSeq, toStopSeq })
}
