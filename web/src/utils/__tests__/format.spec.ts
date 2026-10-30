import { describe, expect, it } from 'vitest'
import { fenToYuan, yuan, formatTripTime, countdownText } from '@/utils/format'

describe('金额格式化', () => {
  it('分转元保留两位', () => {
    expect(fenToYuan(4000)).toBe('40.00')
    expect(fenToYuan(4050)).toBe('40.50')
    expect(fenToYuan(null)).toBe('0.00')
  })
  it('元金额保留两位', () => {
    expect(yuan(40)).toBe('40.00')
  })
})

describe('行程时间格式化', () => {
  it('ISO 字符串 → 中文日期+时间', () => {
    expect(formatTripTime('2026-10-30T18:00:00')).toBe('10月30日 18:00')
  })
  it('非法输入降级返回原串', () => {
    expect(formatTripTime('')).toBe('--')
    expect(formatTripTime(null)).toBe('--')
  })
})

describe('倒计时文案', () => {
  it('天数优先', () => {
    expect(countdownText(2 * 86400 * 1000 + 3600 * 1000)).toBe('还剩 2天1小时')
  })
  it('小时优先', () => {
    expect(countdownText(3600 * 1000 + 60 * 1000)).toBe('还剩 1小时1分')
  })
  it('分钟兜底', () => {
    expect(countdownText(65 * 1000)).toBe('还剩 1分5秒')
  })
  it('到期', () => {
    expect(countdownText(0)).toBe('已到期')
  })
})
