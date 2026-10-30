<script setup lang="ts">
/** 我的：登录（注册/登录合页）+ 我的订单入口 + AI 助手入口 + 行程发布 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GTabBar, GCard, GInput, GButton, GToast } from '@/g-ui'
import { login, type UserInfo } from '@/api/user'
import { setToken } from '@/api/request'
import { myOrders, type Order } from '@/api/order'

const router = useRouter()
const tab = ref('mine')
const user = ref<UserInfo | null>(null)
const phone = ref('13900139000')
const password = ref('123456')
const toast = ref(false)
const toastMsg = ref('')
const orders = ref<Order[]>([])
const loginOpen = ref(false)

onMounted(async () => {
  const token = localStorage.getItem('goshare-token')
  if (!token) {
    loginOpen.value = true
    return
  }
  // 简化：有 token 视为已登录（真实场景调 /user/me）
  user.value = { id: 2, phone: '13900139000', role: 'PASSENGER' }
  try {
    orders.value = await myOrders()
  } catch {
    /* 未登录则静默 */
  }
})

async function doLogin() {
  try {
    const r = await login(phone.value, password.value)
    setToken(r.token)
    user.value = r.user
    loginOpen.value = false
    toastMsg.value = '登录成功'
    toast.value = true
    orders.value = await myOrders()
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  }
}
</script>

<template>
  <div class="mine">
    <GNavBar title="我的" :show-back="false" />

    <GCard v-if="user" class="mine__card">
      <p class="mine__name">{{ user.phone }}</p>
      <p class="mine__role">{{ user.role === 'DRIVER' ? '司机' : '乘客' }} · 信任 {{ user.trustScore ?? '5.0' }}</p>
    </GCard>
    <GCard v-else class="mine__card">
      <p class="mine__name">未登录</p>
      <GButton type="secondary" block @click="loginOpen = true">登录</GButton>
    </GCard>

    <div class="mine__menu">
      <div class="mine__item" @click="router.push('/assistant')">
        <span>AI 出行助手</span><span class="mine__arrow">›</span>
      </div>
      <div class="mine__item" @click="router.push('/coupons')">
        <span>我的券包</span><span class="mine__arrow">›</span>
      </div>
      <div class="mine__item" @click="router.push('/search')">
        <span>发布行程（先搜索验证需求）</span><span class="mine__arrow">›</span>
      </div>
    </div>

    <h3 v-if="orders.length" class="mine__section">最近订单</h3>
    <div v-if="orders.length" class="mine__orders">
      <GCard v-for="o in orders.slice(0, 3)" :key="o.orderNo" class="mine__order" @click="router.push(`/pay/${o.orderNo}`)">
        <span class="num">{{ o.orderNo }}</span>
        <span :class="`mine__order-status mine__order-status--${o.status}`">{{ o.status }}</span>
      </GCard>
    </div>

    <GTabBar v-model="tab" :tabs="[
      { key: 'home', label: '首页', icon: 'home' },
      { key: 'search', label: '搜索', icon: 'search' },
      { key: 'coupons', label: '券包', icon: 'coupons' },
      { key: 'mine', label: '我的', icon: 'mine' },
    ]" />

    <!-- 登录抽屉（GSheet 演示） -->
    <div v-if="loginOpen" class="mine__login-mask" @click="loginOpen = false"></div>
    <div v-if="loginOpen" class="mine__login safe-bottom">
      <p class="mine__login-title">登录 GoShare</p>
      <GInput v-model="phone" label="手机号" />
      <GInput v-model="password" type="password" label="密码" />
      <GButton type="primary" block @click="doLogin">登录</GButton>
      <GButton type="text" block @click="loginOpen = false">暂不登录</GButton>
    </div>

    <GToast v-model="toast" :message="toastMsg" />
  </div>
</template>

<style scoped>
.mine {
  position: relative;
  min-height: 100vh;
  padding: 0 var(--sp-2) 56px;
}
.mine__card {
  margin: var(--sp-2) 0;
}
.mine__name {
  font-size: var(--fs-20);
  font-weight: var(--fw-semibold);
}
.mine__role {
  margin-top: 4px;
  font-size: var(--fs-13);
  color: var(--text-2);
}
.mine__menu {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1), var(--highlight);
  overflow: hidden;
}
.mine__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 48px;
  padding: 0 var(--sp-2);
  border-bottom: 1px solid var(--border);
  font-size: var(--fs-14);
  cursor: pointer;
}
.mine__item:last-child {
  border-bottom: none;
}
.mine__arrow {
  color: var(--text-3);
  font-size: var(--fs-20);
}
.mine__section {
  margin: var(--sp-3) 0 var(--sp-2);
  font-size: var(--fs-16);
}
.mine__order {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--sp-1);
  cursor: pointer;
}
.mine__order-status {
  font-size: var(--fs-12);
}
.mine__order-status--PAID {
  color: var(--c-green-500);
}
.mine__order-status--CREATED {
  color: var(--c-orange-500);
}
.mine__order-status--CANCELLED {
  color: var(--text-3);
}
/* 登录抽屉（复用 GSheet 视觉） */
.mine__login-mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(0, 0, 0, 0.45);
}
.mine__login {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 101;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-2);
  background: var(--bg-base);
  border-radius: var(--r-xl) var(--r-xl) 0 0;
  box-shadow: var(--shadow-3);
}
.mine__login-title {
  font-size: var(--fs-20);
  font-weight: var(--fw-semibold);
  text-align: center;
}
</style>
