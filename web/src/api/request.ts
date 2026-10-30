import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'

/** 统一响应包装（与后端 Result<T> 对齐：code/message/data） */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

const TOKEN_KEY = 'goshare-token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

const request = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

// token 注入
request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 统一错误处理：业务码非 200 抛错；401 清 token
request.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResult
    if (body && body.code !== 200) {
      if (body.code === 401) {
        localStorage.removeItem(TOKEN_KEY)
      }
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return resp
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
    }
    return Promise.reject(new Error(err.response?.data?.message || err.message || '网络异常'))
  },
)

/** 泛型请求助手：直接返回 data */
export async function apiGet<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const resp = await request.get<ApiResult<T>>(url, config)
  return resp.data.data
}

export async function apiPost<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const resp = await request.post<ApiResult<T>>(url, body, config)
  return resp.data.data
}

export default request
