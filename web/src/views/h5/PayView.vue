<script setup lang="ts">
/** 支付页：订单信息 + 余额模拟支付 + 结果（等宽倒计时） */
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { GNavBar, GCard, GButton, GSkeleton, GError } from '@/g-ui'
import { getOrder, payOrder, type Order } from '@/api/order'
import { countdownText, yuan, formatTripTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const order = ref<Order | null>(null)
const loading = ref(true)
const error = ref('')
const paying = ref(false)
const paid = ref(false)
/** 支付截止 = 订单创建时间 + 15 分钟（服务端订单超时释放口径一致），刷新页面不重置 */
const leftMs = ref(15 * 60 * 1000)
const expired = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const PAY_WINDOW_MS = 15 * 60 * 1000

onMounted(async () => {
  const orderNo = route.params.orderNo as string
  try {
    order.value = await getOrder(orderNo)
    // 服务端仅 CREATED 可支付：已支付/超时/取消等状态直接提示，避免「点了才报错」
    if (order.value.status !== 'CREATED') {
      error.value =
        order.value.status === 'PAID'
          ? '订单已支付，无需重复支付'
          : '订单当前状态不可支付，请返回重新下单'
      return
    }
    // 服务端截止时间：createdAt + 15min（与订单超时释放扫描口径一致）
    const deadline = new Date(order.value.createdAt).getTime() + PAY_WINDOW_MS
    leftMs.value = Math.max(0, deadline - Date.now())
    if (leftMs.value <= 0) expired.value = true
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
  // 每秒递减；到 0 视为超时失效，停止倒计时
  timer = setInterval(() => {
    leftMs.value = Math.max(0, leftMs.value - 1000)
    if (leftMs.value === 0) {
      expired.value = true
      if (timer) clearInterval(timer)
    }
  }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

async function pay() {
  if (!order.value) return
  paying.value = true
  try {
    await payOrder(order.value.orderNo, 'BALANCE')
    paid.value = true
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    paying.value = false
  }
}

/** 支付成功 → 回到首页（我的订单可查看该行程） */
function goHome() {
  router.push('/')
}
</script>

<template>
  <div class="pay">
    <GNavBar title="收银台" @back="router.back()" />

    <GSkeleton v-if="loading" :rows="4" :height="20" />
    <GError v-else-if="error" :message="error" @retry="router.go(0)" />

    <template v-else-if="order">
      <GCard class="pay__card">
        <p class="pay__label">待支付金额</p>
        <p class="pay__amount num"><b>{{ yuan(order.payPrice) }}</b> 元</p>
        <template v-if="order.tripFromCity && order.tripToCity">
          <p class="pay__route">{{ order.tripFromCity }} → {{ order.tripToCity }}<span v-if="order.tripDepartureTime" class="pay__route-time num">发车 {{ formatTripTime(order.tripDepartureTime) }}</span></p>
        </template>
        <p v-if="!expired" class="pay__countdown num">{{ countdownText(leftMs) }}，请尽快完成支付</p>
        <p v-else class="pay__countdown pay__countdown--expired num">已超时，订单已关闭</p>
        <p class="pay__no num">订单号 {{ order.orderNo }}</p>
      </GCard>

      <GCard class="pay__card">
        <div class="pay__row">
          <span>座位数</span><span class="num">{{ order.seatCount }} 座</span>
        </div>
        <div class="pay__row">
          <span>优惠</span><span class="num">-{{ yuan(order.couponDiscount) }} 元</span>
        </div>
        <div class="pay__row">
          <span>支付方式</span><span>余额（模拟）</span>
        </div>
      </GCard>

      <div class="pay__footer safe-bottom">
        <GButton type="primary" block :loading="paying" :disabled="expired" @click="paid ? goHome() : pay()">
          {{ paid ? '回到首页' : expired ? '订单已超时' : '立即支付' }}
        </GButton>
        <p v-if="paid" class="pay__done">✓ 支付成功，可回到首页查看</p>
        <p v-else-if="expired" class="pay__expired">支付超时订单已关闭，可重新下单</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pay {
  position: relative;
  min-height: 100vh;
  padding-bottom: 90px;
}

.pay__card {
  margin: var(--sp-2);
}

.pay__label {
  font-size: var(--fs-13);
  color: var(--text-3);
}

.pay__amount {
  margin-top: var(--sp-1);
  font-size: var(--fs-16);
  color: var(--text-1);
}

.pay__amount b {
  font-size: var(--fs-32);
  color: var(--c-orange-500);
}

.pay__countdown {
  margin-top: var(--sp-1);
  font-size: var(--fs-13);
  color: var(--text-2);
}

.pay__countdown--expired {
  color: var(--c-red-500);
}

.pay__expired {
  margin-top: var(--sp-2);
  text-align: center;
  font-size: var(--fs-14);
  color: var(--text-3);
}

.pay__no {
  margin-top: var(--sp-1);
  font-size: var(--fs-12);
  color: var(--text-3);
}

.pay__route {
  margin-top: var(--sp-1);
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--sp-1);
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
  color: var(--text-1);
}

.pay__route-time {
  font-size: var(--fs-12);
  font-weight: normal;
  color: var(--text-2);
}

.pay__row {
  display: flex;
  justify-content: space-between;
  padding: var(--sp-1) 0;
  font-size: var(--fs-14);
  color: var(--text-1);
}

.pay__footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: var(--sp-2);
  background: color-mix(in srgb, var(--bg-base) 78%, transparent);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
  border-top: 1px solid var(--border);
}

.pay__done {
  margin-top: var(--sp-2);
  text-align: center;
  font-size: var(--fs-14);
  color: var(--c-green-500);
}
</style>
