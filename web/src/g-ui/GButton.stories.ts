import type { Meta, StoryObj } from '@storybook/vue3'
import GButton from './GButton.vue'

const meta = {
  title: '基础/GButton 按钮',
  component: GButton,
  tags: ['autodocs'],
} satisfies Meta<typeof GButton>

export default meta
type Story = StoryObj<typeof GButton>

export const 主按钮: Story = {
  args: { type: 'primary' },
  render: (args) => ({ components: { GButton }, setup: () => ({ args }), template: `<GButton v-bind="args">立即下单</GButton>` }),
}
export const 次按钮: Story = {
  args: { type: 'secondary' },
  render: (args) => ({ components: { GButton }, setup: () => ({ args }), template: `<GButton v-bind="args">查看详情</GButton>` }),
}
export const 文字按钮: Story = {
  args: { type: 'text' },
  render: (args) => ({ components: { GButton }, setup: () => ({ args }), template: `<GButton v-bind="args">更多</GButton>` }),
}
export const 禁用态: Story = {
  args: { type: 'primary', disabled: true },
  render: (args) => ({ components: { GButton }, setup: () => ({ args }), template: `<GButton v-bind="args">不可点击</GButton>` }),
}
export const 加载态: Story = {
  args: { type: 'primary', loading: true },
  render: (args) => ({ components: { GButton }, setup: () => ({ args }), template: `<GButton v-bind="args">处理中</GButton>` }),
}
export const 通栏按钮: Story = {
  args: { type: 'primary', block: true },
  render: (args) => ({ components: { GButton }, setup: () => ({ args }), template: `<GButton v-bind="args">通栏占座</GButton>` }),
}
