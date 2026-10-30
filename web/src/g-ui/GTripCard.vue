<script setup lang="ts">
/** GTripCard：起终点、时间、价格、余量热度条、车主信任分；信息层级 ≤3 行 */
import { computed } from 'vue'
import { formatTripTime } from '@/utils/format'

const props = defineProps<{
  trip: {
    tripId?: number
    id?: number
    fromCity?: string
    toCity?: string
    departureTime?: string
    price?: number | string
    remainSeats?: number
    totalSeats?: number
    trustScore?: number | string
    remark?: string
  }
  active?: boolean
}>()

const emit = defineEmits<{ click: [] }>()
const remain = computed(() => Number(props.trip.remainSeats ?? 0))
const total = computed(() => Number(props.trip.totalSeats ?? remain.value))
const ratio = computed(() => (total.value > 0 ? Math.min(1, remain.value / total.value) : 0))
const statusText = computed(() => (remain.value <= 0 ? '已满座' : `余 ${remain.value} 座`))
const hot = computed(() => (ratio.value <= 0.33 ? '热' : ratio.value <= 0.66 ? '中' : '冷'))
</script>

<template>
  <article class="g-trip-card" :class="{ 'g-trip-card--active': active }" @click="emit('click')">
    <div class="g-trip-card__route">
      <span class="g-trip-card__city">{{ trip.fromCity }}</span>
      <span class="g-trip-card__arrow" aria-hidden="true">→</span>
      <span class="g-trip-card__city">{{ trip.toCity }}</span>
      <span class="g-trip-card__time">{{ formatTripTime(trip.departureTime) }}</span>
    </div>
    <div class="g-trip-card__meta">
      <span class="g-trip-card__price num"><b>{{ trip.price }}</b> 元</span>
      <span class="g-trip-card__seats" :class="`g-trip-card__seats--${hot}`">{{ statusText }}</span>
      <span class="g-trip-card__trust num">信任 {{ trip.trustScore ?? '5.0' }}</span>
    </div>
    <!-- 余量热度条 -->
    <div class="g-trip-card__bar" aria-hidden="true">
      <div class="g-trip-card__bar-inner" :style="{ width: `${ratio * 100}%` }"></div>
    </div>
  </article>
</template>

<style scoped>
.g-trip-card {
  padding: var(--sp-2);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1), var(--highlight);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
  cursor: pointer;
  transition: transform var(--dur-fast) var(--ease-out-expo), border-color var(--dur-fast);
}
.g-trip-card:active {
  transform: scale(0.98);
}
.g-trip-card--active {
  border-color: var(--brand);
}
.g-trip-card__route {
  display: flex;
  align-items: baseline;
  gap: var(--sp-1);
}
.g-trip-card__city {
  font-size: var(--fs-20);
  font-weight: var(--fw-semibold);
  color: var(--text-1);
}
.g-trip-card__arrow {
  color: var(--text-3);
}
.g-trip-card__time {
  margin-left: auto;
  font-size: var(--fs-14);
  color: var(--text-2);
}
.g-trip-card__meta {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-1);
  font-size: var(--fs-13);
  color: var(--text-2);
}
.g-trip-card__price {
  color: var(--c-orange-500);
}
.g-trip-card__price b {
  font-size: var(--fs-20);
}
.g-trip-card__seats {
  padding: 2px var(--sp-1);
  border-radius: var(--r-sm);
  font-size: var(--fs-12);
}
.g-trip-card__seats--热 {
  color: var(--c-red-500);
  background: color-mix(in srgb, var(--c-red-500) 14%, transparent);
}
.g-trip-card__seats--中 {
  color: var(--c-orange-500);
  background: color-mix(in srgb, var(--c-orange-500) 14%, transparent);
}
.g-trip-card__seats--冷 {
  color: var(--c-green-500);
  background: color-mix(in srgb, var(--c-green-500) 14%, transparent);
}
.g-trip-card__trust {
  margin-left: auto;
  color: var(--text-3);
}
.g-trip-card__bar {
  height: 3px;
  margin-top: var(--sp-1);
  border-radius: 2px;
  background: var(--surface-strong);
  overflow: hidden;
}
.g-trip-card__bar-inner {
  height: 100%;
  border-radius: 2px;
  background: var(--brand-grad);
  transition: width var(--dur-base);
}
</style>
