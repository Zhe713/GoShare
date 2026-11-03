<script setup lang="ts">
/** GCouponCard：左金额区 + 右信息区 + 虚线撕口；四态：可领/已领/已领完/已用（灰化+印章） */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    coupon?: {
      amount?: number | string
      minSpend?: number | string
      name?: string
      desc?: string
      expire?: string
      status?: 'available' | 'claimed' | 'soldout' | 'used'
      /** 金额单位：¥ 现金券 / 折 折扣券（如 8.5折） */
      unit?: '¥' | '折'
      /** 限量信息（秒杀券展示「限量 x 张 · 已抢 y 张」） */
      soldInfo?: string
    }
  }>(),
  { coupon: () => ({ amount: 0, minSpend: 0, name: '', desc: '', expire: '', status: 'available' as const, unit: '¥' as const, soldInfo: '' }) },
)

const emit = defineEmits<{ action: [] }>()

const status = computed(() => props.coupon.status ?? 'available')
const statusText = computed(() =>
  status.value === 'used' ? '已使用' : status.value === 'claimed' ? '已领取' : status.value === 'soldout' ? '已抢完' : '去领取',
)
</script>

<template>
  <div class="g-coupon" :class="`g-coupon--${status}`">
    <div class="g-coupon__amount">
      <span class="g-coupon__sym">{{ coupon.unit === '折' ? '' : '¥' }}</span>
      <span class="g-coupon__num num">{{ coupon.amount }}</span>
      <span class="g-coupon__cond">{{ coupon.unit === '折' ? '折' : `满 ${coupon.minSpend} 可用` }}</span>
    </div>
    <!-- 虚线撕口 -->
    <div class="g-coupon__perf"></div>
    <div class="g-coupon__info">
      <p class="g-coupon__name">{{ coupon.name }}</p>
      <p class="g-coupon__desc">{{ coupon.desc }}</p>
      <p v-if="coupon.soldInfo" class="g-coupon__sold">{{ coupon.soldInfo }}</p>
      <p class="g-coupon__expire">有效期至 {{ coupon.expire }}</p>
    </div>
    <button class="g-coupon__action" :disabled="status !== 'available'" @click="emit('action')">
      {{ statusText }}
    </button>
    <span v-if="status === 'used'" class="g-coupon__stamp">已用</span>
  </div>
</template>

<style scoped>
.g-coupon {
  position: relative;
  display: flex;
  align-items: stretch;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1), var(--highlight);
  overflow: hidden;
  transition: opacity var(--dur-base);
}

.g-coupon__amount {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-width: 96px;
  padding: var(--sp-2);
  background: var(--brand-grad);
  color: var(--on-brand);
}

.g-coupon__sym {
  font-size: var(--fs-14);
}

.g-coupon__num {
  font-size: var(--fs-32);
  font-weight: var(--fw-semibold);
  line-height: 1.1;
}

.g-coupon__cond {
  font-size: var(--fs-12);
  opacity: 0.9;
}

/* 虚线撕口：两端圆点 + 中线虚线 */
.g-coupon__perf {
  position: relative;
  width: 0;
  border-left: 1px dashed var(--border);
  margin: 0 var(--sp-2);
}

.g-coupon__perf::before,
.g-coupon__perf::after {
  content: '';
  position: absolute;
  left: -6px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--bg-base);
  border: 1px solid var(--border);
}

.g-coupon__perf::before {
  top: -7px;
}

.g-coupon__perf::after {
  bottom: -7px;
}

.g-coupon__info {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 2px;
  padding: var(--sp-2) 0;
}

.g-coupon__name {
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
  color: var(--text-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.g-coupon__desc,
.g-coupon__expire,
.g-coupon__sold {
  font-size: var(--fs-12);
  color: var(--text-3);
}

.g-coupon__sold {
  color: var(--c-orange-500);
}

.g-coupon__action {
  align-self: center;
  margin-right: var(--sp-2);
  min-height: 32px;
  padding: 0 var(--sp-2);
  color: var(--on-brand);
  background: var(--brand-grad);
  border-radius: var(--r-sm);
  font-size: var(--fs-13);
}

/* 三态 */
.g-coupon--claimed .g-coupon__amount {
  background: color-mix(in srgb, var(--c-blue-500) 55%, var(--surface));
}

.g-coupon--claimed .g-coupon__action,
.g-coupon--soldout .g-coupon__action {
  color: var(--brand);
  background: color-mix(in srgb, var(--brand) 12%, transparent);
}

.g-coupon--soldout {
  opacity: 0.7;
}

.g-coupon--soldout .g-coupon__amount {
  background: color-mix(in srgb, var(--text-3) 25%, var(--surface));
  color: var(--text-2);
}

.g-coupon--used {
  opacity: 0.6;
}

.g-coupon--used .g-coupon__amount {
  background: var(--surface-strong);
  color: var(--text-3);
}

.g-coupon--used .g-coupon__action {
  display: none;
}

/* 已用印章 */
.g-coupon__stamp {
  position: absolute;
  right: var(--sp-2);
  bottom: var(--sp-1);
  padding: 2px var(--sp-1);
  border: 1px solid var(--text-3);
  border-radius: var(--r-sm);
  color: var(--text-3);
  font-size: var(--fs-12);
  transform: rotate(-8deg);
}
</style>
