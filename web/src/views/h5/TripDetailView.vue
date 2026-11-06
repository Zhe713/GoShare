<script setup lang="ts">
/** 行程详情：起终点/时间/余座/价格 + 座位选择 + 优惠券选择 + 下单（领券→下单抵扣→支付优惠全链路） */
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { GNavBar, GCard, GButton, GSkeleton, GError } from '@/g-ui'
import { getTrip, type Trip } from '@/api/trip'
import { createOrder } from '@/api/order'
import { myCoupons, type Coupon } from '@/api/user'
import { yuan, formatTripTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const trip = ref<Trip | null>(null)
const loading = ref(true)
const error = ref('')
const seats = ref(1)
const creating = ref(false)
const coupons = ref<Coupon[]>([])
const couponId = ref<number | null>(null)
onMounted(async () => {
  try {
    trip.value = await getTrip(route.params.id as string)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
  try {
    coupons.value = await myCoupons()
  } catch {
    /* 未登录/失败静默，仅无券可选 */
  }
})

const total = computed(() => Number(trip.value?.price ?? 0) * seats.value)
const maxSeats = computed(() => Math.min(4, trip.value?.remainSeats ?? 1))

/** 与后端 CouponCalculator 一致的抵扣金额（元） */
function calcDiscount(c: Coupon): number {
  const base = total.value
  if (c.type === 'FULL_REDUCTION') {
    return base >= Number(c.threshold) ? Math.min(Number(c.discountValue), base) : 0
  }
  if (c.type === 'CASH') return Math.min(Number(c.discountValue), base)
  if (c.type === 'DISCOUNT') {
    const pay = Math.round(base * Number(c.discountValue)) / 100
    return Math.max(0, Math.round((base - pay) * 100) / 100)
  }
  return 0
}

/** 可用券：未使用 + 未过期 + 当前订单确实能抵扣（满减未满门槛的不可选） */
const usableCoupons = computed(() =>
  coupons.value
    .filter((c) => c.status === 0 && (!c.expireAt || new Date(c.expireAt).getTime() > Date.now()) && calcDiscount(c) > 0)
    .sort((a, b) => calcDiscount(b) - calcDiscount(a)),
)

const couponDiscount = computed(() => (couponId.value ? calcDiscount(usableCoupons.value.find((c) => c.id === couponId.value)!) : 0))
const payTotal = computed(() => Math.max(0, Math.round((total.value - couponDiscount.value) * 100) / 100))

function couponLabel(c: Coupon) {
  const name =
    c.type === 'FULL_REDUCTION'
      ? `满${c.threshold}减${c.discountValue}`
      : c.type === 'DISCOUNT'
        ? `${c.discountValue / 10}折券`
        : `立减${c.discountValue}元`
  return `${name}（可抵 ${calcDiscount(c)} 元）`
}

async function confirmOrder() {
  if (!trip.value) return
  creating.value = true
  try {
    const order = await createOrder({
      tripId: trip.value.id,
      seatCount: seats.value,
      couponId: couponId.value ?? undefined,
      idempotentKey: `web-${Date.now()}`,
    })
    router.push(`/pay/${order.orderNo}`)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <div class="detail">
    <GNavBar title="行程详情" @back="router.back()" />

    <GSkeleton v-if="loading" :rows="6" :height="18" />
    <GError v-else-if="error" :message="error" @retry="router.go(0)" />

    <template v-else-if="trip">
      <GCard class="detail__card">
        <div class="detail__route">
          <div class="detail__city">
            <p class="detail__city-name">{{ trip.fromCity }}</p>
            <p class="detail__city-tag">出发</p>
          </div>
          <div class="detail__line" aria-hidden="true"></div>
          <div class="detail__city">
            <p class="detail__city-name">{{ trip.toCity }}</p>
            <p class="detail__city-tag">到达</p>
          </div>
        </div>
        <div class="detail__meta">
          <span class="num">{{ formatTripTime(trip.departureTime) }}</span>
          <span class="num">余 {{ trip.remainSeats }}/{{ trip.totalSeats }} 座</span>
          <span class="detail__price num"><b>{{ trip.price }}</b> 元/座</span>
        </div>
        <p v-if="trip.remark" class="detail__remark">{{ trip.remark }}</p>
      </GCard>

      <!-- 车主与车辆信任信息（阶段1新增：下单前能看到"上谁的车"） -->
      <GCard v-if="trip.vehiclePlate || trip.driverNickname || trip.stops?.length" class="detail__card">
        <h3 class="detail__section-title">车主与车辆</h3>
        <div v-if="trip.driverNickname || trip.vehiclePlate" class="detail__driver">
          <span v-if="trip.driverNickname" class="detail__driver-name">{{ trip.driverNickname }}</span>
          <span v-if="trip.vehicleColor" class="detail__driver-tag">{{ trip.vehicleColor }}</span>
          <span v-if="trip.vehiclePlate" class="detail__driver-tag">{{ trip.vehiclePlate }}</span>
          <span v-if="trip.vehicleModel" class="detail__driver-tag">{{ trip.vehicleModel }}</span>
        </div>
        <!-- 站点序列：起终点 + 途经点 -->
        <div v-if="trip.stops?.length" class="detail__stops">
          <div v-for="(s, i) in trip.stops" :key="i" class="detail__stop">
            <span class="detail__stop-dot" :class="{ 'detail__stop-dot--end': i === trip.stops.length - 1 }"></span>
            <div class="detail__stop-info">
              <p class="detail__stop-city">{{ s.city }}</p>
              <p v-if="s.address" class="detail__stop-addr">{{ s.address }}</p>
            </div>
          </div>
        </div>
        <p v-if="trip.totalDistanceKm" class="detail__remark">全程约 {{ trip.totalDistanceKm }} 公里</p>
      </GCard>

      <GCard class="detail__card">
        <div class="detail__seats">
          <span>选择座位</span>
          <button
            v-for="n in maxSeats"
            :key="n"
            class="detail__seat"
            :class="{ 'detail__seat--on': seats === n }"
            @click="seats = n"
          >
            {{ n }}
          </button>
        </div>
        <p class="detail__total num">合计：<b>{{ total }}</b> 元</p>
      </GCard>

      <GCard class="detail__card">
        <div class="detail__coupon-head">
          <span>优惠券</span>
          <button v-if="!usableCoupons.length" class="detail__coupon-go" @click="router.push('/coupons')">去领券 ›</button>
        </div>
        <label v-for="c in usableCoupons" :key="c.id" class="detail__coupon" :class="{ 'detail__coupon--on': couponId === c.id }">
          <input type="radio" :value="c.id" v-model="couponId" class="detail__coupon-radio" />
          <span class="detail__coupon-name">{{ couponLabel(c) }}</span>
        </label>
        <label v-if="usableCoupons.length" class="detail__coupon detail__coupon--none">
          <input type="radio" :value="null" v-model="couponId" class="detail__coupon-radio" />
          <span class="detail__coupon-name">不使用优惠券</span>
        </label>
        <p v-if="!usableCoupons.length" class="detail__coupon-empty">暂无可用优惠券（订单满减/折扣不适用）</p>
        <p v-if="couponDiscount > 0" class="detail__total num">优惠：-{{ yuan(couponDiscount) }} 元　实付：<b>{{ yuan(payTotal) }}</b> 元</p>
      </GCard>

      <div class="detail__footer safe-bottom">
        <GButton type="primary" block :loading="creating" @click="confirmOrder">立即下单占座</GButton>
      </div>
    </template>
  </div>
</template>

<style scoped>
.detail {
  position: relative;
  min-height: 100vh;
  padding-bottom: 90px;
}

.detail__card {
  margin: var(--sp-2);
}

.detail__route {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.detail__city-name {
  font-size: var(--fs-24);
  font-weight: var(--fw-semibold);
}

.detail__city-tag {
  font-size: var(--fs-12);
  color: var(--text-3);
}

.detail__line {
  flex: 1;
  margin: 0 var(--sp-2);
  border-top: 2px dashed var(--border);
  position: relative;
}

.detail__line::after {
  content: '→';
  position: absolute;
  right: 0;
  top: -14px;
  color: var(--text-3);
}

.detail__meta {
  display: flex;
  gap: var(--sp-2);
  margin-top: var(--sp-2);
  font-size: var(--fs-13);
  color: var(--text-2);
}

.detail__price {
  margin-left: auto;
  color: var(--c-orange-500);
}

.detail__price b {
  font-size: var(--fs-20);
}

.detail__remark {
  margin-top: var(--sp-1);
  font-size: var(--fs-13);
  color: var(--text-2);
}

.detail__section-title {
  font-size: var(--fs-14);
  font-weight: var(--fw-semibold);
  margin-bottom: var(--sp-2);
}

.detail__driver {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  flex-wrap: wrap;
}

.detail__driver-name {
  font-size: var(--fs-15);
  font-weight: var(--fw-semibold);
  color: var(--text-1);
}

.detail__driver-tag {
  font-size: var(--fs-12);
  color: var(--brand);
  background: color-mix(in srgb, var(--brand) 10%, transparent);
  padding: 2px 8px;
  border-radius: var(--r-sm);
}

.detail__stops {
  margin-top: var(--sp-2);
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}

.detail__stop {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
}

.detail__stop-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand);
  margin-top: 6px;
  flex-shrink: 0;
}

.detail__stop-dot--end {
  background: var(--c-orange-500);
}

.detail__stop-city {
  font-size: var(--fs-14);
  color: var(--text-1);
}

.detail__stop-addr {
  font-size: var(--fs-12);
  color: var(--text-3);
}

.detail__seats {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.detail__seat {
  min-width: 44px;
  height: 44px;
  border-radius: var(--r-md);
  background: var(--surface);
  border: 1px solid var(--border);
  color: var(--text-1);
  font-size: var(--fs-16);
}

.detail__seat--on {
  background: var(--brand-grad);
  color: var(--on-brand);
  border-color: transparent;
}

.detail__total {
  margin-top: var(--sp-2);
  font-size: var(--fs-16);
}

.detail__total b {
  font-size: var(--fs-24);
  color: var(--c-orange-500);
}

.detail__coupon-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: var(--fs-14);
  color: var(--text-1);
}

.detail__coupon-go {
  color: var(--brand);
  font-size: var(--fs-13);
}

.detail__coupon {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  margin-top: var(--sp-1);
  padding: var(--sp-1) var(--sp-2);
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  cursor: pointer;
}

.detail__coupon--on {
  border-color: var(--brand);
  background: color-mix(in srgb, var(--brand) 10%, transparent);
}

.detail__coupon-radio {
  accent-color: var(--brand);
}

.detail__coupon-name {
  font-size: var(--fs-13);
  color: var(--text-1);
}

.detail__coupon-empty {
  margin-top: var(--sp-1);
  font-size: var(--fs-12);
  color: var(--text-3);
}

.detail__footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: var(--sp-2);
  background: color-mix(in srgb, var(--bg-base) 78%, transparent);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
  border-top: 1px solid var(--border);
}
</style>
