<script setup lang="ts">
/** 车主行程管理：我发布的行程 + 每单乘客名单 + 发车/到达确认（履约闭环） */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GCard, GButton, GToast, GEmpty } from '@/g-ui'
import { myTrips, cancelTrip, departTrip, completeTrip, type Trip } from '@/api/trip'
import { formatTripTime } from '@/utils/format'
import { ordersByTrip, boardPassenger, completePassenger, type Order } from '@/api/order'

const router = useRouter()
const trips = ref<Trip[]>([])
const loading = ref(true)
const toast = ref(false)
const toastMsg = ref('')
/** 当前展开乘客名单的行程 */
const expandedTrip = ref<number | null>(null)
const passengers = ref<Record<number, Order[]>>({})

onMounted(loadTrips)

async function loadTrips() {
  loading.value = true
  try {
    trips.value = await myTrips()
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  } finally {
    loading.value = false
  }
}

async function togglePassengers(tripId: number) {
  if (expandedTrip.value === tripId) {
    expandedTrip.value = null
    return
  }
  expandedTrip.value = tripId
  try {
    passengers.value[tripId] = await ordersByTrip(tripId)
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  }
}

function statusText(s: number) {
  return s === 0 ? '待出发' : s === 1 ? '满座' : s === 2 ? '进行中' : s === 3 ? '已完成' : s === 4 ? '已取消' : String(s)
}

function orderStatusText(s: string) {
  return s === 'PAID' ? '已支付' : s === 'BOARDED' ? '已上车' : s === 'COMPLETED' ? '已完成' : s === 'REVIEWED' ? '已评价' : s === 'REFUNDED' ? '已退款' : s === 'REFUNDING' ? '退款中' : s
}

async function onCancel(tripId: number) {
  try {
    await cancelTrip(tripId)
    toastMsg.value = '行程已取消，相关订单已批量退单退款'
    toast.value = true
    loadTrips()
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  }
}

async function onDepart(tripId: number) {
  try {
    await departTrip(tripId)
    toastMsg.value = '已标记发车'
    toast.value = true
    loadTrips()
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  }
}

async function onComplete(tripId: number) {
  try {
    await completeTrip(tripId)
    toastMsg.value = '已标记到达完成'
    toast.value = true
    loadTrips()
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  }
}

async function onBoard(tripId: number, orderNo: string) {
  try {
    await boardPassenger(tripId, orderNo)
    toastMsg.value = '已确认乘客上车'
    toast.value = true
    togglePassengers(tripId)
    setTimeout(() => togglePassengers(tripId), 100)
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  }
}

async function onArrive(tripId: number, orderNo: string) {
  try {
    await completePassenger(tripId, orderNo)
    toastMsg.value = '已确认乘客到达'
    toast.value = true
    togglePassengers(tripId)
    setTimeout(() => togglePassengers(tripId), 100)
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  }
}
</script>

<template>
  <div class="driver">
    <GNavBar title="我的行程（车主）" @back="router.back()" />

    <div class="driver__top">
      <GButton type="primary" block @click="router.push('/publish')">＋ 发布新行程</GButton>
    </div>

    <GEmpty v-if="!loading && !trips.length" text="还没有发布行程" />

    <GCard v-for="t in trips" :key="t.id" class="driver__card">
      <div class="driver__route">
        <span class="driver__route-text">{{ t.fromCity }} → {{ t.toCity }}</span>
        <span class="driver__status" :class="`driver__status--${t.status}`">{{ statusText(t.status) }}</span>
      </div>
      <div class="driver__meta">
        <span>{{ formatTripTime(t.departureTime) }}</span>
        <span>余 {{ t.remainSeats }}/{{ t.totalSeats }} 座</span>
      </div>
      <div v-if="t.vehiclePlate" class="driver__vehicle">车辆：{{ t.vehiclePlate }}{{ t.vehicleModel ? ' · ' + t.vehicleModel : '' }}</div>

      <div class="driver__actions">
        <GButton v-if="t.status === 0 || t.status === 1" type="text" size="sm" @click="onDepart(t.id)">发车</GButton>
        <GButton v-if="t.status === 2" type="text" size="sm" @click="onComplete(t.id)">到达</GButton>
        <GButton v-if="t.status === 0 || t.status === 1" type="text" size="sm" @click="onCancel(t.id)">取消行程</GButton>
        <GButton type="text" size="sm" @click="togglePassengers(t.id)">{{ expandedTrip === t.id ? '收起乘客' : '乘客名单' }}</GButton>
      </div>

      <!-- 乘客名单 + 履约操作 -->
      <div v-if="expandedTrip === t.id" class="driver__passengers">
        <p v-if="!passengers[t.id]?.length" class="driver__passenger-empty">暂无已支付乘客</p>
        <div v-for="o in passengers[t.id] || []" :key="o.orderNo" class="driver__passenger">
          <div class="driver__passenger-main">
            <span class="driver__passenger-no num">{{ o.orderNo }}</span>
            <span class="driver__passenger-status">{{ orderStatusText(o.status) }}</span>
          </div>
          <div class="driver__passenger-sub">{{ o.seatCount }} 座 · 实付 {{ o.payPrice }} 元</div>
          <div class="driver__passenger-actions">
            <GButton v-if="o.status === 'PAID'" type="text" size="sm" @click="onBoard(t.id, o.orderNo)">确认上车</GButton>
            <GButton v-if="o.status === 'BOARDED'" type="text" size="sm" @click="onArrive(t.id, o.orderNo)">确认到达</GButton>
          </div>
        </div>
      </div>
    </GCard>

    <GToast v-model="toast" :message="toastMsg" />
  </div>
</template>

<style scoped>
.driver {
  min-height: 100vh;
  padding-bottom: 24px;
}

.driver__top {
  margin: var(--sp-2);
}

.driver__card {
  margin: var(--sp-2);
}

.driver__route {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.driver__route-text {
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
}

.driver__status {
  font-size: var(--fs-12);
  padding: 2px 8px;
  border-radius: var(--r-sm);
  background: var(--surface);
  color: var(--text-2);
}

.driver__status--2 {
  color: var(--brand);
}

.driver__status--3 {
  color: var(--c-green-500);
}

.driver__status--4 {
  color: var(--text-3);
}

.driver__meta {
  display: flex;
  gap: var(--sp-2);
  margin-top: var(--sp-1);
  font-size: var(--fs-12);
  color: var(--text-2);
}

.driver__vehicle {
  margin-top: var(--sp-1);
  font-size: var(--fs-12);
  color: var(--text-2);
}

.driver__actions {
  display: flex;
  gap: var(--sp-1);
  margin-top: var(--sp-2);
  flex-wrap: wrap;
}

.driver__passengers {
  margin-top: var(--sp-2);
  padding-top: var(--sp-2);
  border-top: 1px dashed var(--border);
}

.driver__passenger {
  padding: var(--sp-1) 0;
  border-bottom: 1px solid var(--border);
}

.driver__passenger:last-child {
  border-bottom: none;
}

.driver__passenger-main {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.driver__passenger-no {
  font-size: var(--fs-12);
  color: var(--text-2);
}

.driver__passenger-status {
  font-size: var(--fs-12);
  color: var(--brand);
}

.driver__passenger-sub {
  margin-top: 2px;
  font-size: var(--fs-12);
  color: var(--text-3);
}

.driver__passenger-actions {
  display: flex;
  gap: var(--sp-1);
  margin-top: var(--sp-1);
}

.driver__passenger-empty {
  font-size: var(--fs-13);
  color: var(--text-3);
  text-align: center;
  padding: var(--sp-2) 0;
}
</style>
