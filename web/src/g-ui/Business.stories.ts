import type { Meta, StoryObj } from '@storybook/vue3'
import GTripCard from './GTripCard.vue'
import GCouponCard from './GCouponCard.vue'
import GChatBubble from './GChatBubble.vue'

/* ========== GTripCard（default meta）========== */
export default {
  title: '业务/GTripCard 行程卡',
  component: GTripCard,
  tags: ['autodocs'],
} satisfies Meta<typeof GTripCard>

type TStory = StoryObj<typeof GTripCard>

export const 热卖行程: TStory = {
  render: (args) => ({ components: { GTripCard }, setup: () => ({ args }), template: `<div style="max-width:420px"><GTripCard v-bind="args" /></div>` }),
  args: { trip: { id: 1, fromCity: '石家庄', toCity: '邯郸', departureTime: '2026-10-30T18:00:00', price: 40, remainSeats: 1, totalSeats: 3, trustScore: '4.8' } },
}
export const 余座充足: TStory = {
  render: (args) => ({ components: { GTripCard }, setup: () => ({ args }), template: `<div style="max-width:420px"><GTripCard v-bind="args" /></div>` }),
  args: { trip: { id: 2, fromCity: '石家庄', toCity: '北京', departureTime: '2026-10-31T10:00:00', price: 60, remainSeats: 4, totalSeats: 4, trustScore: '5.0' } },
}
export const 已满座: TStory = {
  render: (args) => ({ components: { GTripCard }, setup: () => ({ args }), template: `<div style="max-width:420px"><GTripCard v-bind="args" /></div>` }),
  args: { trip: { id: 3, fromCity: '石家庄', toCity: '北京', departureTime: '2026-10-30T20:30:00', price: 35, remainSeats: 0, totalSeats: 2, trustScore: '4.6' } },
}

/* ========== GCouponCard ========== */
type CStory = StoryObj<typeof GCouponCard>

export const 可领取: CStory = {
  render: (args) => ({ components: { GCouponCard }, setup: () => ({ args }), template: `<div style="max-width:420px"><GCouponCard v-bind="args" /></div>` }),
  args: { coupon: { amount: 10, minSpend: 50, name: '新客满50减10', desc: '新客专享', expire: '11-05', status: 'available' } },
}
export const 已领取: CStory = {
  render: (args) => ({ components: { GCouponCard }, setup: () => ({ args }), template: `<div style="max-width:420px"><GCouponCard v-bind="args" /></div>` }),
  args: { coupon: { amount: 5, minSpend: 0, name: '限时秒杀立减5元', desc: '秒杀活动', expire: '11-05', status: 'claimed' } },
}
export const 已使用: CStory = {
  render: (args) => ({ components: { GCouponCard }, setup: () => ({ args }), template: `<div style="max-width:420px"><GCouponCard v-bind="args" /></div>` }),
  args: { coupon: { amount: 10, minSpend: 50, name: '新客满50减10', desc: '新客专享', expire: '11-05', status: 'used' } },
}

/* ========== GChatBubble ========== */
type ChStory = StoryObj<typeof GChatBubble>

export const 用户气泡: ChStory = {
  render: (args) => ({ components: { GChatBubble }, setup: () => ({ args }), template: `<GChatBubble v-bind="args" />` }),
  args: { role: 'user', text: '周五18点从石家庄去邯郸，能带大件吗' },
}
export const 助手气泡: ChStory = {
  render: (args) => ({ components: { GChatBubble }, setup: () => ({ args }), template: `<div style="max-width:420px"><GChatBubble v-bind="args" /></div>` }),
  args: { role: 'assistant', text: '为你找到 2 个行程：18:00 发车 40 元余 3 座（可带大件），评分 0.94；20:30 发车 35 元余 2 座。' },
}
