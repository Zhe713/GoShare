<script setup lang="ts">
/**
 * 订单详情页：订单状态 + 去支付 + A2 取消挽留
 * A2 流程：点「取消订单」→ 先调 /assistant/cancel-preview →
 * 抽屉里展示「费用明细 + 规则说明 + 替代行程 + 挽留话术」→ 用户二次确认才真正取消。
 * 铁律：金额与规则全部来自后端（order 侧 FeeCalculator 纯 Java 计算），前端不自己算、不编话术。
 */
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { GNavBar, GCard, GButton, GSkeleton, GError, GSheet, GToast } from '@/g-ui'
import { getOrder, cancelOrder, cancelPreview, rescheduleOrder, type Order, type CancelRetention } from '@/api/order'
import { yuan, formatTripTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const order = ref<Order | null>(null)
const loading = ref(true)
const error = ref('')
const toast = ref(false)
const toastMsg = ref('')

/** A2 挽留抽屉状态 */
const sheetOpen = ref(false)
const previewing = ref(false)
const cancelling = ref(false)
const rescheduling = ref(false)
const retention = ref<CancelRetention | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    order.value = await getOrder(route.params.orderNo as string)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}

function tip(msg: string) {
  toastMsg.value = msg
  toast.value = true
}

const STATUS_TEXT: Record<string, string> = {
  CREATED: '待支付',
  PAID: '已支付',
  IN_SERVICE: '服务中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  EXPIRED: '已超时关闭',
}

/** 点取消：先拿挽留预览（含费用与替代行程），成功后再开抽屉 */
async function openRetention() {
  if (!order.value || previewing.value) return
  previewing.value = true
  try {
    retention.value = await cancelPreview(order.value.orderNo)
    sheetOpen.value = true
  } catch (e) {
    tip((e as Error).message)
  } finally {
    previewing.value = false
  }
}

/** 抽屉内二次确认：真正取消 */
async function confirmCancel() {
  if (!order.value || cancelling.value) return
  cancelling.value = true
  try {
    const updated = await cancelOrder(order.value.orderNo, '用户主动取消')
    // 后端取消接口返回的 VO 不含行程摘要，这里合并以保留页面已有展示
    order.value = { ...order.value, ...(updated ?? {}), status: updated?.status ?? 'CANCELLED' }
    sheetOpen.value = false
    tip('订单已取消')
  } catch (e) {
    tip((e as Error).message)
  } finally {
    cancelling.value = false
  }
}

/** F4 一键改签：同线路新班次，改签免费、差额自动退 */
async function doReschedule(newTripId: number) {
  if (!order.value || rescheduling.value) return
  rescheduling.value = true
  try {
    const updated = await rescheduleOrder(order.value.orderNo, newTripId)
    order.value = { ...order.value, ...(updated ?? {}) }
    sheetOpen.value = false
    tip(updated?.refundAmount ? `改签成功，差额 ${updated.refundAmount} 元已退回` : '改签成功')
  } catch (e) {
    tip((e as Error).message)
  } finally {
    rescheduling.value = false
  }
}
</script>

<template>
  <div class="order">
    <GNavBar title="订单详情" @back="router.back()" />

    <GSkeleton v-if="loading" :rows="5" :height="16" />
    <GError v-else-if="error" :message="error" @retry="load" />

    <template v-else-if="order">
      <GCard class="order__card">
        <div class="order__head">
          <p class="order__status" :class="`order__status--${order.status}`">
            {{ STATUS_TEXT[order.status] ?? order.status }}
          </p>
          <span class="order__no num">{{ order.orderNo }}</span>
        </div>

        <p v-if="order.tripFromCity && order.tripToCity" class="order__route">
          {{ order.tripFromCity }} → {{ order.tripToCity }}
        </p>

        <div class="order__row">
          <span>行程编号</span><span class="num">{{ order.tripId }}</span>
        </div>
        <div v-if="order.tripDepartureTime" class="order__row">
          <span>发车时间</span><span class="num">{{ formatTripTime(order.tripDepartureTime) }}</span>
        </div>
        <div v-if="order.createdAt" class="order__row">
          <span>下单时间</span><span class="num">{{ formatTripTime(order.createdAt) }}</span>
        </div>
        <div class="order__row">
          <span>座位</span><span class="num">{{ order.seatCount }} 座</span>
        </div>
        <div class="order__row">
          <span>订单金额</span><span class="num">{{ yuan(order.totalPrice) }} 元</span>
        </div>
        <div v-if="Number(order.couponDiscount) > 0" class="order__row">
          <span>优惠券抵扣</span><span class="order__row-cut num">-{{ yuan(order.couponDiscount) }} 元</span>
        </div>
        <div class="order__row order__row--total">
          <span>实付</span>
          <span class="order__money num">{{ yuan(order.payPrice) }} 元</span>
        </div>

        <p v-if="order.cancelReason" class="order__reason">取消原因：{{ order.cancelReason }}</p>
      </GCard>

      <div class="order__actions">
        <GButton
          v-if="order.status === 'CREATED'"
          type="primary"
          block
          @click="router.push(`/pay/${order.orderNo}`)"
        >
          去支付
        </GButton>
        <GButton
          v-if="order.status === 'CREATED' || order.status === 'PAID'"
          type="secondary"
          block
          :loading="previewing"
          @click="openRetention"
        >
          取消订单（先看费用说明）
        </GButton>
      </div>
    </template>

    <!-- A2 取消挽留抽屉：费用解释 + 替代行程 + 挽留话术 -->
    <GSheet v-model="sheetOpen" title="取消前先看一下">
      <template v-if="retention">
        <p v-if="retention.pitch" class="retention__pitch">{{ retention.pitch }}</p>
        <p v-if="retention.alreadyRetained" class="retention__repeat">该订单已挽留过一次，本次可直接取消。</p>

        <GCard v-if="retention.fee" class="retention__card">
          <p class="retention__label">费用说明</p>
          <div class="retention__row">
            <span>订单金额</span><span class="num">{{ retention.fee.totalPrice }} 元</span>
          </div>
          <div class="retention__row">
            <span>已实付</span><span class="num">{{ retention.fee.payPrice }} 元</span>
          </div>
          <div class="retention__row">
            <span>取消责任</span><span>{{ retention.fee.liability }}</span>
          </div>
          <div class="retention__row">
            <span>违约金</span><span class="num">{{ retention.fee.penalty }} 元</span>
          </div>
          <div class="retention__row retention__row--refund">
            <span>预计退款</span><span class="num">{{ retention.fee.refundAmount }} 元</span>
          </div>
          <p class="retention__explain">{{ retention.fee.feeExplain }}</p>
          <p v-if="retention.fee.ruleExplain" class="retention__rule">{{ retention.fee.ruleExplain }}</p>
        </GCard>

        <GCard v-if="retention.alternatives?.length" class="retention__card">
          <p class="retention__label">{{ order?.status === 'PAID' ? '同线路可改签班次（改签免费）' : '同路线替代行程' }}</p>
          <div v-for="a in retention.alternatives" :key="a.tripId" class="retention__alt">
            <span class="retention__alt-route">{{ a.fromCity }} → {{ a.toCity }}</span>
            <span class="retention__alt-meta num">{{ formatTripTime(a.departureTime) }} · {{ yuan(a.price) }} 元 · 余{{ a.remainSeats }}座</span>
            <!-- F4 一键改签：仅已支付订单显示（未支付订单直接换班重下即可） -->
            <GButton
              v-if="order?.status === 'PAID' && a.remainSeats > 0"
              type="primary"
              size="sm"
              :loading="rescheduling"
              @click="doReschedule(a.tripId)"
            >
              改签至此班
            </GButton>
          </div>
        </GCard>

        <div class="retention__actions">
          <GButton type="secondary" block @click="sheetOpen = false">我再想想</GButton>
          <GButton type="primary" block :loading="cancelling" @click="confirmCancel">仍要取消</GButton>
        </div>
      </template>
      <GSkeleton v-else :rows="3" :height="14" />
    </GSheet>

    <GToast v-model="toast" :message="toastMsg" />
  </div>
</template>

<style scoped>
.order {
  position: relative;
  min-height: 100vh;
  padding: 0 var(--sp-2) var(--sp-3);
}

.order__card {
  margin: var(--sp-2) 0;
}

.order__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-1);
  margin-bottom: var(--sp-1);
}

.order__status {
  font-size: var(--fs-20);
  font-weight: var(--fw-semibold);
}

.order__no {
  font-size: var(--fs-12);
  color: var(--text-3);
}

.order__status--PAID,
.order__status--COMPLETED {
  color: var(--c-green-500);
}

.order__status--CREATED {
  color: var(--c-orange-500);
}

.order__status--CANCELLED,
.order__status--EXPIRED {
  color: var(--text-3);
}

.order__route {
  margin-bottom: var(--sp-1);
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
  color: var(--text-1);
}

.order__row {
  display: flex;
  justify-content: space-between;
  padding: var(--sp-1) 0;
  font-size: var(--fs-14);
  color: var(--text-2);
}

.order__row--total {
  border-top: 1px solid var(--border);
  margin-top: var(--sp-1);
  color: var(--text-1);
  font-weight: var(--fw-medium);
}

.order__row-cut {
  color: var(--c-red-500);
}

.order__money {
  color: var(--c-orange-500);
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
}

.order__reason {
  margin-top: var(--sp-1);
  font-size: var(--fs-13);
  color: var(--text-3);
}

.order__actions {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  margin-top: var(--sp-2);
}

/* ===== A2 挽留抽屉 ===== */
.retention__pitch {
  margin: var(--sp-1) 0;
  padding: var(--sp-2);
  border-radius: var(--r-md);
  background: var(--surface);
  border: 1px solid var(--border);
  font-size: var(--fs-14);
  line-height: var(--lh-base);
  color: var(--text-1);
}

.retention__repeat {
  margin-bottom: var(--sp-1);
  font-size: var(--fs-13);
  color: var(--text-3);
}

.retention__card {
  margin-bottom: var(--sp-2);
}

.retention__label {
  margin-bottom: var(--sp-1);
  font-size: var(--fs-13);
  color: var(--text-3);
}

.retention__row {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  font-size: var(--fs-14);
  color: var(--text-2);
}

.retention__row--refund {
  margin-top: var(--sp-1);
  padding-top: var(--sp-1);
  border-top: 1px solid var(--border);
  color: var(--text-1);
  font-weight: var(--fw-medium);
}

.retention__explain,
.retention__rule {
  margin-top: var(--sp-1);
  font-size: var(--fs-13);
  line-height: var(--lh-base);
  color: var(--text-2);
}

.retention__rule {
  color: var(--text-3);
}

.retention__alt {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--sp-1) 0;
  border-bottom: 1px solid var(--border);
  cursor: pointer;
}

.retention__alt:last-child {
  border-bottom: none;
}

.retention__alt-route {
  font-size: var(--fs-14);
  font-weight: var(--fw-medium);
  color: var(--text-1);
}

.retention__alt-meta {
  font-size: var(--fs-12);
  color: var(--text-2);
}

.retention__actions {
  display: flex;
  gap: var(--sp-1);
  margin-top: var(--sp-2);
}
</style>
