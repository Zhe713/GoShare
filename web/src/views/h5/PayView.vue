<script setup lang="ts">
/** 支付页：订单信息 + 余额模拟支付 + 结果（等宽倒计时） */
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { GNavBar, GCard, GButton, GSkeleton, GError } from '@/g-ui'
import { getOrder, payOrder, type Order } from '@/api/order'
import { countdownText } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const order = ref<Order | null>(null)
const loading = ref(true)
const error = ref('')
const paying = ref(false)
const paid = ref(false)
const leftMs = ref(15 * 60 * 1000)
let timer: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  const orderNo = route.params.orderNo as string
  try {
    order.value = await getOrder(orderNo)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
  // 15 分钟支付倒计时（等宽数字）
  timer = setInterval(() => (leftMs.value -= 1000), 1000)
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
</script>

<template>
  <div class="pay">
    <GNavBar title="收银台" @back="router.back()" />

    <GSkeleton v-if="loading" :rows="4" :height="20" />
    <GError v-else-if="error" :message="error" @retry="router.go(0)" />

    <template v-else-if="order">
      <GCard class="pay__card">
        <p class="pay__label">待支付金额</p>
        <p class="pay__amount num"><b>{{ order.payPrice }}</b> 元</p>
        <p class="pay__countdown num">{{ countdownText(leftMs) }}</p>
        <p class="pay__no num">订单号 {{ order.orderNo }}</p>
      </GCard>

      <GCard class="pay__card">
        <div class="pay__row">
          <span>座位数</span><span class="num">{{ order.seatCount }} 座</span>
        </div>
        <div class="pay__row">
          <span>优惠</span><span class="num">-{{ order.couponDiscount }} 元</span>
        </div>
        <div class="pay__row">
          <span>支付方式</span><span>余额（模拟）</span>
        </div>
      </GCard>

      <div class="pay__footer safe-bottom">
        <GButton type="primary" block :loading="paying" @click="pay">
          {{ paid ? '支付成功' : '立即支付' }}
        </GButton>
        <p v-if="paid" class="pay__done">✓ 支付成功，可回到首页查看</p>
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
.pay__no {
  margin-top: var(--sp-1);
  font-size: var(--fs-12);
  color: var(--text-3);
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
