import { apiGet, apiPost } from './request'

/** 订单 */
export interface Order {
  orderNo: string
  tripId: number
  passengerId: number
  seatCount: number
  totalPrice: number
  couponDiscount: number
  payPrice: number
  status: string
  paymentNo: string | null
  cancelReason: string | null
}

export function createOrder(body: {
  tripId: number
  seatCount: number
  couponId?: number
  idempotentKey: string
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

/** A2 取消挽留预览（费用解释 + 替代方案 + 话术） */
export interface CancelRetention {
  alreadyRetained: boolean
  fee: {
    orderNo: string
    status: string
    totalPrice: string
    payPrice: string
    liability: string
    penalty: string
    refundAmount: string
    feeExplain: string
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
