<script setup lang="ts">
/** 券包：领券中心 + 我的券（GCouponCard 三态演示） */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GTabBar, GCouponCard, GSkeleton, GEmpty, GToast } from '@/g-ui'
import { myCoupons, claimCoupon, COUPON_TEMPLATES, type Coupon } from '@/api/user'

const router = useRouter()
const tab = ref('coupons')
const coupons = ref<Coupon[]>([])
const templates = ref<{ id: number; name: string; amount: number; minSpend: number; note: string }[]>(COUPON_TEMPLATES)
const loading = ref(true)
const toast = ref(false)
const toastMsg = ref('')

async function load() {
  loading.value = true
  try {
    coupons.value = await myCoupons()
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
</script>

<template>
  <div class="coupons">
    <GNavBar title="券包" @back="router.back()" />

    <h3 class="coupons__section">领券中心</h3>
    <div v-if="templates.length" class="coupons__list">
      <GCouponCard
        v-for="t in templates"
        :key="t.id"
        :coupon="{ amount: t.amount, minSpend: t.minSpend, name: t.name, desc: t.note, status: 'available' }"
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
  padding-bottom: 56px;
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
