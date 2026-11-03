<script setup lang="ts">
/** 券包：领券中心（后端模板动态加载）+ 我的券（GCouponCard 三态演示） */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GTabBar, GCouponCard, GSkeleton, GEmpty, GToast } from '@/g-ui'
import { myCoupons, claimCoupon, couponTemplates, type Coupon, type CouponTemplate } from '@/api/user'

const router = useRouter()
const tab = ref('coupons')
const coupons = ref<Coupon[]>([])
const templates = ref<CouponTemplate[]>([])
const loading = ref(true)
const toast = ref(false)
const toastMsg = ref('')
/** 已领取过的模板 ID（领券中心按钮据此置灰，避免「已领完仍可点」） */
const claimedIds = ref<Set<number>>(new Set())

async function load() {
  loading.value = true
  try {
    const [mine, tpls] = await Promise.all([myCoupons(), couponTemplates()])
    coupons.value = mine
    claimedIds.value = new Set(mine.map((c) => c.couponTemplateId))
    templates.value = tpls.filter((t) => t.status === 0)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function claim(templateId: number) {
  try {
    await claimCoupon(templateId)
    toastMsg.value = '领取成功'
    toast.value = true
    await load()
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  }
}

/** 模板 → GCouponCard 展示（可领/已领取/已抢完三态 + 秒杀限量信息 + 折券单位） */
function toCard(t: CouponTemplate) {
  const claimed = claimedIds.value.has(t.id)
  const soldOut = t.receivedCount >= t.totalCount
  const status = claimed ? 'claimed' : soldOut ? 'soldout' : 'available'
  const name = t.templateName
  const isDiscount = t.type === 'DISCOUNT'
  const amount = isDiscount ? Number(t.discountValue) / 10 : Number(t.discountValue)
  const minSpend = Number(t.threshold)
  const isFlash = name.includes('秒杀') || name.includes('限量')
  const desc = claimed
    ? '已领取，每人限领一张'
    : soldOut
      ? '已抢完，下次早点来'
      : isDiscount
        ? '下单自动打折'
        : minSpend > 0
          ? `满${minSpend}元可用`
          : '无门槛'
  return {
    id: t.id,
    name,
    amount,
    minSpend,
    desc,
    status: status as 'available' | 'claimed' | 'soldout' | 'used',
    unit: (isDiscount ? '折' : '¥') as '折' | '¥',
    soldInfo: isFlash ? `限量 ${t.totalCount} 张 · 已抢 ${t.receivedCount} 张` : '',
  }
}

/** 领券中心展示列表 */
const toCards = computed(() => templates.value.map(toCard))
</script>

<template>
  <div class="coupons">
    <GNavBar title="券包" @back="router.back()" />

    <h3 class="coupons__section">领券中心</h3>
    <div v-if="templates.length" class="coupons__list">
      <GCouponCard
        v-for="t in toCards"
        :key="t.id"
        :coupon="t"
        @action="claim(t.id)"
      />
    </div>

    <h3 class="coupons__section">我的券</h3>
    <GSkeleton v-if="loading" :rows="3" :height="20" />
    <GEmpty v-else-if="!coupons.length" text="还没有领券，去领券中心看看" />
    <div v-else class="coupons__list">
      <GCouponCard
        v-for="c in coupons"
        :key="c.id"
        :coupon="{
          amount: c.discountValue,
          minSpend: c.threshold,
          name: c.type === 'FULL_REDUCTION' ? `满${c.threshold}减${c.discountValue}` : c.type === 'DISCOUNT' ? `${c.discountValue / 10}折券` : `立减${c.discountValue}元`,
          expire: c.expireAt ?? '长期',
          status: c.status === 1 ? 'used' : 'claimed',
        }"
      />
    </div>

    <GTabBar v-model="tab" :tabs="[
      { key: 'home', label: '首页', icon: 'home' },
      { key: 'search', label: '搜索', icon: 'search' },
      { key: 'coupons', label: '券包', icon: 'coupons' },
      { key: 'mine', label: '我的', icon: 'mine' },
    ]" />
    <GToast v-model="toast" :message="toastMsg" />
  </div>
</template>

<style scoped>
.coupons {
  position: relative;
  min-height: 100vh;
  padding-bottom: var(--tabbar-h);
}

.coupons__section {
  margin: var(--sp-3) var(--sp-2) var(--sp-2);
  font-size: var(--fs-16);
}

.coupons__list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: 0 var(--sp-2);
}
</style>
