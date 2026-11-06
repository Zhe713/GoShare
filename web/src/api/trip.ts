import { apiGet, apiPost } from './request'

/** 站点（后端 TripStopDTO 结构） */
export interface TripStop {
  city: string
  address?: string
  lng?: number
  lat?: number
  offsetMinutes?: number
}

/** 行程（后端 TripVO 结构） */
export interface Trip {
  id: number
  driverId: number
  fromCity: string
  toCity: string
  departureTime: string
  totalSeats: number
  remainSeats: number
  price: number
  /** 每公里单价（区间计价） */
  pricePerKm?: number
  /** 整段里程（公里） */
  totalDistanceKm?: number
  status: number
  remark: string
  /** 车辆与车主信任信息（阶段1新增） */
  vehiclePlate?: string
  vehicleModel?: string
  vehicleColor?: string
  driverNickname?: string
  /** 站点序列（含起终点与途经点） */
  stops?: TripStop[]
}

/** A1 语义化搜索 */
export interface SemanticSearchResult {
  rawText: string
  parsedQuery: string
  items: {
    tripId: number
    fromCity: string
    toCity: string
    departureTime: string
    remainSeats: number
    price: string
    trustScore: string
    remark: string
    score: number
    reasons: string[]
  }[]
  explanation: string
}

export function searchTrips(fromCity: string, toCity: string, departDate?: string): Promise<Trip[]> {
  const p = new URLSearchParams({ fromCity, toCity })
  if (departDate) p.set('departDate', departDate)
  return apiGet<Trip[]>(`/trip/search?${p.toString()}`)
}

export function getTrip(id: number | string): Promise<Trip> {
  return apiGet<Trip>(`/trip/${id}`)
}

export function semanticSearch(text: string): Promise<SemanticSearchResult> {
  return apiPost<SemanticSearchResult>('/assistant/semantic-search', { text })
}

export function publishTrip(body: {
  fromCity: string
  toCity: string
  departureTime: string
  totalSeats: number
  price: number
  pricePerKm?: number
  stops?: TripStop[]
  vehiclePlate?: string
  vehicleModel?: string
  vehicleColor?: string
  remark?: string
}): Promise<{ id: number }> {
  return apiPost<{ id: number }>('/trip/publish', body)
}

/** 车主取消行程（阶段1新增） */
export function cancelTrip(tripId: number): Promise<Trip> {
  return apiPost<Trip>(`/trip/${tripId}/cancel`, {})
}

/** 我发布的行程（车主视角，阶段1新增） */
export function myTrips(): Promise<Trip[]> {
  return apiGet<Trip[]>('/trip/mine')
}

/** 车主标记发车（阶段1新增） */
export function departTrip(tripId: number): Promise<Trip> {
  return apiPost<Trip>(`/trip/${tripId}/depart`, {})
}

/** 车主标记到达（阶段1新增） */
export function completeTrip(tripId: number): Promise<Trip> {
  return apiPost<Trip>(`/trip/${tripId}/complete`, {})
}
