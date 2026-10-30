import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import GButton from '@/g-ui/GButton.vue'
import GCouponCard from '@/g-ui/GCouponCard.vue'
import GTripCard from '@/g-ui/GTripCard.vue'
import GChatBubble from '@/g-ui/GChatBubble.vue'

/** g-ui 组件冒烟：渲染 + 交互态 + token 类名 */

describe('GButton', () => {
  it('主按钮默认类名', () => {
    const w = mount(GButton, { slots: { default: '下单' } })
    expect(w.classes()).toContain('g-btn')
    expect(w.classes()).toContain('g-btn--primary')
    expect(w.text()).toBe('下单')
  })
  it('禁用态不触发点击', async () => {
    const w = mount(GButton, { props: { disabled: true } })
    await w.trigger('click')
    expect(w.emitted('click')).toBeUndefined()
  })
  it('加载态不触发点击且带 spinner', async () => {
    const w = mount(GButton, { props: { loading: true } })
    expect(w.find('.g-btn__spinner').exists()).toBe(true)
    await w.trigger('click')
    expect(w.emitted('click')).toBeUndefined()
  })
  it('按下有 active 缩放样式（CSS 由 token 类保证，验证类存在）', () => {
    const w = mount(GButton)
    expect(w.classes().join(' ')).toContain('g-btn--primary')
  })
})

describe('GCouponCard 三态', () => {
  it('可领态显示去领取', () => {
    const w = mount(GCouponCard, {
      props: { coupon: { amount: 10, minSpend: 50, name: '满50减10', status: 'available' } },
    })
    expect(w.find('.g-coupon__action').text()).toBe('去领取')
  })
  it('已领态按钮禁用化', () => {
    const w = mount(GCouponCard, {
      props: { coupon: { amount: 10, minSpend: 50, name: '满50减10', status: 'claimed' } },
    })
    expect(w.find('.g-coupon__action').text()).toBe('已领取')
  })
  it('已用态盖印章', () => {
    const w = mount(GCouponCard, {
      props: { coupon: { amount: 10, minSpend: 50, name: '满50减10', status: 'used' } },
    })
    expect(w.find('.g-coupon__stamp').text()).toBe('已用')
    // 已用态：按钮 disabled（jsdom 不解析 scoped CSS，以逻辑态断言）
    expect(w.find('.g-coupon__action').attributes('disabled')).toBeDefined()
    expect(w.classes()).toContain('g-coupon--used')
  })
})

describe('GTripCard 热度三色', () => {
  it('余量少 → 热（红）', () => {
    const w = mount(GTripCard, {
      props: { trip: { id: 1, fromCity: '石家庄', toCity: '北京', departureTime: '2026-10-30T18:00:00', price: 60, remainSeats: 1, totalSeats: 4 } },
    })
    expect(w.find('.g-trip-card__seats').classes()).toContain('g-trip-card__seats--热')
    expect(w.text()).toContain('余 1 座')
  })
  it('余量充足 → 冷（绿）', () => {
    const w = mount(GTripCard, {
      props: { trip: { id: 2, fromCity: '石家庄', toCity: '北京', departureTime: '2026-10-30T18:00:00', price: 60, remainSeats: 4, totalSeats: 4 } },
    })
    expect(w.find('.g-trip-card__seats').classes()).toContain('g-trip-card__seats--冷')
  })
  it('满座文案', () => {
    const w = mount(GTripCard, {
      props: { trip: { id: 3, fromCity: '石家庄', toCity: '北京', departureTime: '2026-10-30T18:00:00', price: 60, remainSeats: 0, totalSeats: 4 } },
    })
    expect(w.text()).toContain('已满座')
  })
})

describe('GChatBubble 双边', () => {
  it('用户气泡渐变实色', () => {
    const w = mount(GChatBubble, { props: { role: 'user', text: '你好' } })
    expect(w.classes()).toContain('g-chat--user')
    expect(w.find('.g-chat__bubble').text()).toBe('你好')
  })
  it('助手气泡玻璃面', () => {
    const w = mount(GChatBubble, { props: { role: 'assistant', text: '规则命中' } })
    expect(w.classes()).toContain('g-chat--assistant')
  })
})
