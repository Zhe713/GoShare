<script setup lang="ts">
/** 行程详情：起终点/时间/余座/价格 + 座位选择 + 下单 */
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { GNavBar, GCard, GButton, GSkeleton, GError } from '@/g-ui'
import { getTrip, type Trip } from '@/api/trip'
import { createOrder } from '@/api/order'

const route = useRoute()
const router = useRouter()
const trip = ref<Trip | null>(null)
const loading = ref(true)
const error = ref('')
const seats = ref(1)
const creating = ref(false)
onMounted(async () => {
  try {
    trip.value = await getTrip(route.params.id as string)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
})

const total = computed(() => Number(trip.value?.price ?? 0) * seats.value)
const maxSeats = computed(() => Math.min(4, trip.value?.remainSeats ?? 1))

async function confirmOrder() {
  if (!trip.value) return
  creating.value = true
  try {
    const order = await createOrder({
      tripId: trip.value.id,
      seatCount: seats.value,
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
          <span class="num">{{ trip.departureTime }}</span>
          <span class="num">余 {{ trip.remainSeats }}/{{ trip.totalSeats }} 座</span>
          <span class="detail__price num"><b>{{ trip.price }}</b> 元/座</span>
        </div>
        <p v-if="trip.remark" class="detail__remark">{{ trip.remark }}</p>
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
