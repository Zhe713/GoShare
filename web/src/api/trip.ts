import { apiGet, apiPost } from './request'

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
  status: number
  remark: string
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
  remark?: string
}): Promise<{ id: number }> {
  return apiPost<{ id: number }>('/trip/publish', body)
}
