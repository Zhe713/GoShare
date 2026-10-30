import type { Meta, StoryObj } from '@storybook/vue3'
import GCard from './GCard.vue'
import GInput from './GInput.vue'
import GNavBar from './GNavBar.vue'
import GTabBar from './GTabBar.vue'
import GDialog from './GDialog.vue'
import GToast from './GToast.vue'
import GSkeleton from './GSkeleton.vue'
import GEmpty from './GEmpty.vue'
import GError from './GError.vue'
import GSheet from './GSheet.vue'

/* ========== GCard ========== */
const cardMeta = {
  title: '基础/GCard 卡片',
  component: GCard,
  tags: ['autodocs'],
} satisfies Meta<typeof GCard>
export default cardMeta
type CStory = StoryObj<typeof GCard>

export const 常规卡片: CStory = {
  render: () => ({ components: { GCard }, template: `<GCard style="max-width:360px"><h3>石家庄 → 北京</h3><p>10月30日 18:00 出发</p></GCard>` }),
}
export const 紧凑卡片: CStory = {
  render: () => ({ components: { GCard }, setup: () => ({ padding: 'compact' }), template: `<GCard padding="compact" style="max-width:360px">紧凑间距</GCard>` }),
}

/* ========== GInput ========== */
type IStory = StoryObj<typeof GInput>
export const 输入框: IStory = {
  render: (args) => ({ components: { GInput }, setup: () => ({ args }), template: `<GInput v-bind="args" />` }),
  args: { placeholder: '搜索目的地', label: '目的地' },
}
export const 错误态: IStory = {
  render: (args) => ({ components: { GInput }, setup: () => ({ args }), template: `<GInput v-bind="args" />` }),
  args: { placeholder: '手机号', label: '手机号', error: '手机号格式不正确' },
}
export const 禁用态: IStory = {
  render: (args) => ({ components: { GInput }, setup: () => ({ args }), template: `<GInput v-bind="args" />` }),
  args: { placeholder: '不可编辑', disabled: true },
}

/* ========== GNavBar ========== */
type NStory = StoryObj<typeof GNavBar>
export const 导航栏: NStory = {
  render: (args) => ({ components: { GNavBar }, setup: () => ({ args }), template: `<GNavBar v-bind="args" />` }),
  args: { title: 'GoShare 顺风拼车' },
}

/* ========== GTabBar ========== */
type TStory = StoryObj<typeof GTabBar>
export const 底部导航: TStory = {
  render: (args) => ({
    components: { GTabBar },
    setup: () => ({ args }),
    template: `<div style="position:relative;height:120px;width:100%"><GTabBar v-bind="args" style="position:static"/></div>`,
  }),
  args: { modelValue: 'home', tabs: [
    { key: 'home', label: '首页', icon: '⌂' },
    { key: 'search', label: '搜索', icon: '⌕' },
    { key: 'coupons', label: '券包', icon: '✂' },
    { key: 'mine', label: '我的', icon: '☺' },
  ] },
}

/* ========== GDialog ========== */
type DStory = StoryObj<typeof GDialog>
export const 确认弹窗: DStory = {
  render: (args) => ({
    components: { GDialog },
    setup: () => ({ args }),
    template: `<GDialog v-bind="args" />`,
  }),
  args: { modelValue: true, title: '确认取消行程', message: '取消后座位将释放，券会退回', confirmText: '确认取消', cancelText: '再想想' },
}

/* ========== GToast ========== */
type ToStory = StoryObj<typeof GToast>
export const 顶部提示: ToStory = {
  render: (args) => ({
    components: { GToast },
    setup: () => ({ args }),
    template: `<GToast v-bind="args" />`,
  }),
  args: { modelValue: true, message: '领取成功' },
}

/* ========== GSkeleton ========== */
type SStory = StoryObj<typeof GSkeleton>
export const 加载骨架: SStory = {
  render: (args) => ({
    components: { GSkeleton },
    setup: () => ({ args }),
    template: `<div style="max-width:360px"><GSkeleton v-bind="args" /></div>`,
  }),
  args: { rows: 4, height: 16 },
}

/* ========== GEmpty ========== */
type EStory = StoryObj<typeof GEmpty>
export const 空状态: EStory = {
  render: (args) => ({ components: { GEmpty }, setup: () => ({ args }), template: `<GEmpty v-bind="args" />` }),
  args: { text: '暂无行程', actionText: '去发布' },
}

/* ========== GError ========== */
type ErStory = StoryObj<typeof GError>
export const 错误重试: ErStory = {
  render: (args) => ({ components: { GError }, setup: () => ({ args }), template: `<GError v-bind="args" />` }),
  args: { message: '网络开了小差，请检查后重试' },
}

/* ========== GSheet ========== */
type ShStory = StoryObj<typeof GSheet>
export const 底部抽屉: ShStory = {
  render: (args) => ({
    components: { GSheet },
    setup: () => ({ args }),
    template: `<GSheet v-bind="args"><p style="padding:24px 0;text-align:center">抽屉内容区</p></GSheet>`,
  }),
  args: { modelValue: true, title: '选择座位' },
}
