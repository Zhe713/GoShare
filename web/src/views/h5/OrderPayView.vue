<script setup lang="ts">
/** 订单页（下单确认 → 待支付）：展示订单三态 + 取消按钮（A2 挽留入口） */
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { GNavBar, GCard, GButton, GSkeleton, GError, GDialog, GToast } from '@/g-ui'
import { getOrder, cancelOrder, cancelPreview, type Order } from '@/api/order'
import { fenToYuan } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const order = ref<Order | null>(null)
const loading = ref(true)
const error = ref('')
const cancelOpen = ref(false)
const toast = ref(false)
const toastMsg = ref('')

onMounted(async () => {
  try {
    order.value = await getOrder(route.params.orderNo as string)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
})

async function doCancel() {
  if (!order.value) return
  try {
    // 先走 A2 挽留预览（后端算费用 + 给替代方案）
    await cancelPreview(order.value.orderNo)
    await cancelOrder(order.value.orderNo, '用户主动取消')
    toastMsg.value = '已取消（A2 已给出费用解释与替代行程）'
    toast.value = true
    order.value.status = 'CANCELLED'
  } catch (e) {
    error.value = (e as Error).message
  }
}
</script>

<template>
  <div class="order">
    <GNavBar title="我的订单" @back="router.back()" />

    <GSkeleton v-if="loading" :rows="4" :height="16" />
    <GError v-else-if="error" :message="error" @retry="router.go(0)" />

    <template v-else-if="order">
      <GCard class="order__card">
        <p class="order__status" :class="`order__status--${order.status}`">{{ order.status }}</p>
        <div class="order__row">
          <span>订单号</span><span class="num">{{ order.orderNo }}</span>
        </div>
        <div class="order__row">
          <span>行程编号</span><span class="num">{{ order.tripId }}</span>
        </div>
        <div class="order__row">
          <span>座位</span><span class="num">{{ order.seatCount }} 座</span>
        </div>
        <div class="order__row">
          <span>实付</span>
          <span class="order__money num">{{ fenToYuan(order.payPrice) }} 元</span>
        </div>
      </GCard>

      <div class="order__actions">
        <GButton v-if="order.status === 'PAID' || order.status === 'CREATED'" type="secondary" block @click="cancelOpen = true">
          取消订单（先看费用说明）
        </GButton>
      </div>
    </template>

    <GDialog
      v-model="cancelOpen"
      title="确认取消"
      message="将先按规则计算取消费用，并为你推荐替代行程（A2）"
      confirm-text="确认取消"
      @confirm="doCancel"
    />
    <GToast v-model="toast" :message="toastMsg" />
  </div>
</template>

<style scoped>
.order {
  position: relative;
  min-height: 100vh;
  padding: 0 var(--sp-2);
}
.order__card {
  margin: var(--sp-2) 0;
}
.order__status {
  font-size: var(--fs-20);
  font-weight: var(--fw-semibold);
  margin-bottom: var(--sp-2);
}
.order__status--PAID {
  color: var(--c-green-500);
}
.order__status--CREATED {
  color: var(--c-orange-500);
}
.order__status--CANCELLED {
  color: var(--text-3);
}
.order__row {
  display: flex;
  justify-content: space-between;
  padding: var(--sp-1) 0;
  font-size: var(--fs-14);
  color: var(--text-2);
}
.order__money {
  color: var(--c-orange-500);
  font-weight: var(--fw-medium);
}
.order__actions {
  margin-top: var(--sp-3);
}
</style>
