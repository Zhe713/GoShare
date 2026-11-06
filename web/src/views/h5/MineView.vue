<script setup lang="ts">
/** 我的：登录/注册合页 + 我的订单入口 + AI 助手入口 + 行程发布 + F5 行程账单 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GTabBar, GCard, GInput, GButton, GToast } from '@/g-ui'
import { currentUser, login, register, tripBill, type UserInfo, type TripBill } from '@/api/user'
import { getToken, setToken } from '@/api/request'
import { myOrders, type Order } from '@/api/order'
import { yuan } from '@/utils/format'

const router = useRouter()
const tab = ref('mine')
const user = ref<UserInfo | null>(null)
const phone = ref('')
const password = ref('')
const toast = ref(false)
const toastMsg = ref('')
const orders = ref<Order[]>([])
const loginOpen = ref(false)
/** 登录/注册双模式（补注册入口：此前注册接口无前端入口） */
const isRegister = ref(false)
/** F5 行程账单 */
const bill = ref<TripBill | null>(null)

onMounted(async () => {
  // 无 token 直接引导登录；有 token 则用 /user/me 拿真实用户（不再硬编码）
  if (!getToken()) {
    loginOpen.value = true
    return
  }
  await loadMine()
})

/** 拉取当前用户 + 我的订单 + 行程账单；token 失效（401 已被拦截器清除）时退回登录态 */
async function loadMine() {
  try {
    user.value = await currentUser()
  } catch {
    user.value = null
    orders.value = []
    bill.value = null
    loginOpen.value = true
    return
  }
  try {
    orders.value = await myOrders()
  } catch {
    orders.value = []
  }
  // F5 行程账单（当月聚合，数据全部来自真实订单表；失败静默不阻塞）
  try {
    bill.value = await tripBill()
  } catch {
    bill.value = null
  }
}

async function doLogin() {
  try {
    if (isRegister.value) {
      // 注册：成功即视为登录态建立（后端 register 返回登录响应）
      await register({ phone: phone.value.trim(), password: password.value })
    }
    const r = await login(phone.value.trim(), password.value)
    setToken(r.token)
    // 后端登录响应是扁平结构 { token, userId, nickname, role }，之前写成 r.user 会拿到 undefined；
    // 统一改为拿 token 后回查 /user/me，字段与后端 UserVO 严格对齐
    user.value = await currentUser()
    loginOpen.value = false
    isRegister.value = false
    toastMsg.value = '登录成功'
    toast.value = true
    await loadMine()
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  }
}

/** 订单卡点击：待支付进收银台，其余状态进订单详情（详情页才有取消/费用说明） */
function openOrder(o: Order) {
  router.push(o.status === 'CREATED' ? `/pay/${o.orderNo}` : `/order/${o.orderNo}`)
}

function statusText(s: string) {
  const map: Record<string, string> = {
    PAID: '已支付',
    CREATED: '待支付',
    CANCELLED: '已取消',
    EXPIRED: '已超时',
    BOARDED: '已上车',
    COMPLETED: '已完成',
    REVIEWED: '已评价',
    REFUNDING: '退款中',
    REFUNDED: '已退款',
  }
  return map[s] || s
}

/** 身份文案：role 为主，已认证车主优先展示 */
function roleText(u: UserInfo) {
  if (u.driverVerified === 1) return '认证车主'
  return u.role === 'DRIVER' ? '司机' : '乘客'
}

function doLogout() {
  localStorage.removeItem('goshare-token')
  user.value = null
  orders.value = []
  loginOpen.value = true
}
</script>

<template>
  <div class="mine">
    <GNavBar title="我的" :show-back="false" />

    <GCard v-if="user" class="mine__card">
      <p class="mine__name">{{ user.nickname || user.phone }}</p>
      <p class="mine__role">
        {{ user.nickname ? user.phone + ' · ' : '' }}{{ roleText(user) }} · 信任 {{ user.trustScore ?? '5.0' }}
      </p>
      <GButton class="mine__logout" type="text" block @click="doLogout">退出登录</GButton>
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
      <div v-if="user && (user.role === 'DRIVER' || user.driverVerified === 1)" class="mine__item" @click="router.push('/publish')">
        <span>发布行程（车主）</span><span class="mine__arrow">›</span>
      </div>
      <div v-if="user && (user.role === 'DRIVER' || user.driverVerified === 1)" class="mine__item" @click="router.push('/driver/trips')">
        <span>我的行程 · 乘客管理</span><span class="mine__arrow">›</span>
      </div>
      <div v-else-if="user" class="mine__item" @click="router.push('/certification')">
        <span>车主认证</span><span class="mine__arrow">›</span>
      </div>
      <div class="mine__item" @click="router.push('/support')">
        <span>联系客服（工单秒回）</span><span class="mine__arrow">›</span>
      </div>
    </div>

    <!-- F5 行程账单（当月，数据全部来自真实订单表） -->
    <GCard v-if="bill && bill.orderCount > 0" class="mine__card">
      <div class="mine__bill-head">
        <h3 class="mine__bill-title">{{ bill.month }} 行程账单</h3>
        <span class="mine__bill-count num">共 {{ bill.orderCount }} 单</span>
      </div>
      <div class="mine__bill-grid">
        <div class="mine__bill-cell">
          <p class="mine__bill-num num">{{ bill.totalDistanceKm }}</p>
          <p class="mine__bill-label">总里程（公里）</p>
        </div>
        <div class="mine__bill-cell">
          <p class="mine__bill-num num">{{ yuan(bill.totalPay) }}</p>
          <p class="mine__bill-label">总花费（元）</p>
        </div>
        <div class="mine__bill-cell">
          <p class="mine__bill-num num">{{ yuan(bill.savedEstimate) }}</p>
          <p class="mine__bill-label">较快车估算省（元）</p>
        </div>
      </div>
      <p v-if="bill.topRoutes.length" class="mine__bill-route">
        最常线路：{{ bill.topRoutes.map((r) => `${r.fromCity}→${r.toCity}×${r.count}`).join('　') }}
      </p>
      <p class="mine__bill-note">省钱估算按网约车快车约 2.3 元/公里计算（估算口径）</p>
    </GCard>

    <h3 v-if="orders.length" class="mine__section">最近订单 · 共 {{ orders.length }} 单</h3>
    <div v-if="orders.length" class="mine__orders">
      <GCard v-for="o in orders" :key="o.orderNo" class="mine__order" @click="openOrder(o)">
        <div class="mine__order-main">
          <p class="mine__order-route">
            <template v-if="o.tripFromCity && o.tripToCity">{{ o.tripFromCity }} → {{ o.tripToCity }}</template>
            <template v-else>行程拼车</template>
            <span class="mine__order-price num">{{ yuan(o.payPrice) }} 元</span>
          </p>
          <p class="mine__order-sub">
            <template v-if="o.tripDepartureTime">发车 {{ o.tripDepartureTime }}</template>
            <template v-else>下单 {{ o.createdAt }}</template>
            <span class="mine__order-seats num">{{ o.seatCount }} 座</span>
          </p>
          <p class="mine__order-no num">{{ o.orderNo }}</p>
        </div>
        <span :class="`mine__order-status mine__order-status--${o.status}`">{{ statusText(o.status) }}</span>
      </GCard>
    </div>

    <GTabBar v-model="tab" :tabs="[
      { key: 'home', label: '首页', icon: 'home' },
      { key: 'search', label: '搜索', icon: 'search' },
      { key: 'coupons', label: '券包', icon: 'coupons' },
      { key: 'mine', label: '我的', icon: 'mine' },
    ]" />

    <!-- 登录/注册抽屉（GSheet 演示） -->
    <div v-if="loginOpen" class="mine__login-mask" @click="loginOpen = false"></div>
    <div v-if="loginOpen" class="mine__login safe-bottom">
      <p class="mine__login-title">{{ isRegister ? '注册 GoShare' : '登录 GoShare' }}</p>
      <GInput v-model="phone" label="手机号" :placeholder="isRegister ? '请输入手机号' : '演示账号 13900139000'" />
      <GInput v-model="password" type="password" label="密码" :placeholder="isRegister ? '设置密码' : '演示密码 123456'" />
      <GButton type="primary" block @click="doLogin">{{ isRegister ? '注册并登录' : '登录' }}</GButton>
      <GButton type="text" block @click="isRegister = !isRegister">
        {{ isRegister ? '已有账号？去登录' : '没有账号？注册一个' }}
      </GButton>
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

.mine__logout {
  margin-top: var(--sp-1);
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

.mine__order-main {
  flex: 1;
  min-width: 0;
  padding-right: var(--sp-2);
}

.mine__order-route {
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--sp-1);
}

.mine__order-price {
  color: var(--brand);
  font-size: var(--fs-14);
}

.mine__order-sub {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--sp-1);
  margin-top: 2px;
  font-size: var(--fs-12);
  color: var(--text-2);
}

.mine__order-no {
  margin-top: 4px;
  font-size: var(--fs-12);
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mine__order-status {
  flex-shrink: 0;
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

/* F5 行程账单 */
.mine__bill-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: var(--sp-2);
}

.mine__bill-title {
  font-size: var(--fs-15);
  font-weight: var(--fw-semibold);
}

.mine__bill-count {
  font-size: var(--fs-12);
  color: var(--text-3);
}

.mine__bill-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--sp-1);
  text-align: center;
}

.mine__bill-num {
  font-size: var(--fs-18);
  font-weight: var(--fw-semibold);
  color: var(--c-orange-500);
}

.mine__bill-label {
  margin-top: 2px;
  font-size: var(--fs-12);
  color: var(--text-3);
}

.mine__bill-route {
  margin-top: var(--sp-2);
  font-size: var(--fs-12);
  color: var(--brand);
}

.mine__bill-note {
  margin-top: var(--sp-1);
  font-size: var(--fs-11);
  color: var(--text-3);
}
</style>
