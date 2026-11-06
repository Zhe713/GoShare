<script setup lang="ts">
/** AI 出行助手：对话式（GChatBubble）+ 通用问答 + F1 对话式下单确认卡（AI 只出草稿，点确认才下单） */
import { nextTick, ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GChatBubble, GInput, GButton, GToast } from '@/g-ui'
import { assistantChat, orderDraft, type OrderDraft } from '@/api/user'
import { createOrder } from '@/api/order'
import { formatTripTime } from '@/utils/format'

const router = useRouter()

interface Msg {
  role: 'user' | 'assistant'
  text: string
  meta?: string
  /** F1 确认卡：存在时渲染卡片与「确认下单」按钮 */
  draft?: OrderDraft
}

const messages = ref<Msg[]>([
  {
    role: 'assistant',
    text: '你好，我是 GoShare 出行助手。可以帮你：口语订票（如「从石家庄订明天去邯郸的，两个人」）、口语搜行程、算取消费用、看天气。',
  },
])
const text = ref('')
const sending = ref(false)
const listRef = ref<HTMLElement | null>(null)
const placing = ref(false)
const toast = ref(false)
const toastMsg = ref('')

/** 订票意图检测：含「订/买票/下单」字样走 F1 确认卡链路。
 *  注意排除「订单」（如「取消订单会扣钱吗」是费用问句，不是订票）——用负向前瞻 (?!单)。 */
function isOrderIntent(q: string): boolean {
  return /订(?!单)|买票|下单|帮我买/.test(q)
}

async function send() {
  const q = text.value.trim()
  if (!q || sending.value) return
  messages.value.push({ role: 'user', text: q })
  text.value = ''
  sending.value = true
  try {
    if (isOrderIntent(q)) {
      await sendOrderDraft(q)
    } else {
      await sendChat(q)
    }
  } finally {
    sending.value = false
    await nextTick()
    listRef.value?.scrollTo({ top: 99999, behavior: 'smooth' })
  }
}

/** 普通问答：省 token 四关卡 */
async function sendChat(q: string) {
  const history = messages.value
    .slice(Math.max(0, messages.value.length - 9), -1)
    .map((m) => ({ role: m.role, content: m.text }))
  const r = await assistantChat(q, history)
  const meta = r.fromCache
    ? '缓存命中（关卡1）· 零模型调用'
    : r.modelName.includes('规则')
      ? '规则小模型先答（关卡2）· 零成本'
      : r.modelName
  messages.value.push({ role: 'assistant', text: r.answer, meta })
}

/** F1 对话式下单：确定性解析 + 真实班次 + 确认卡（金额后端算，AI 不碰钱） */
async function sendOrderDraft(q: string) {
  try {
    const draft = await orderDraft(q)
    if (draft.orderable) {
      messages.value.push({
        role: 'assistant',
        text: '已为你找到班次，请确认下单 ↓（金额为预估，优惠券在收银台可选）',
        draft,
      })
    } else {
      messages.value.push({ role: 'assistant', text: draft.message || '暂时没有匹配的班次。' })
    }
  } catch {
    // 草稿接口异常时回落普通问答
    await sendChat(q)
  }
}

/** 确认下单：调真实下单接口 → 跳收银台（券在收银台选） */
async function confirmOrder(draft: OrderDraft) {
  if (!draft.tripId || placing.value) return
  placing.value = true
  try {
    const order = await createOrder({
      tripId: draft.tripId,
      seatCount: draft.seatCount,
      idempotentKey: `ai-${draft.tripId}-${Date.now()}`,
    })
    toastMsg.value = `下单成功 ${order.orderNo}`
    toast.value = true
    setTimeout(() => router.push(`/pay/${order.orderNo}`), 800)
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  } finally {
    placing.value = false
  }
}
</script>

<template>
  <div class="assistant">
    <GNavBar title="AI 出行助手" @back="router.back()" />

    <div ref="listRef" class="assistant__list">
      <div v-for="(m, i) in messages" :key="i">
        <GChatBubble :role="m.role" :text="m.text" />
        <p v-if="m.meta" class="assistant__meta">{{ m.meta }}</p>

        <!-- F1 订单确认卡 -->
        <div v-if="m.draft" class="assistant__draft">
          <div class="assistant__draft-row">
            <span class="assistant__draft-route">{{ m.draft.fromCity }} → {{ m.draft.toCity }}</span>
            <span class="assistant__draft-price num">约 {{ m.draft.estimatedTotal }} 元</span>
          </div>
          <div class="assistant__draft-row assistant__draft-row--sub">
            <span>{{ formatTripTime(m.draft.departureTime) }}</span>
            <span>余 {{ m.draft.remainSeats }} 座 · {{ m.draft.seatCount }} 人</span>
          </div>
          <div v-if="m.draft.stopCities && m.draft.stopCities.length > 2" class="assistant__draft-stops">
            途经：{{ m.draft.stopCities.join(' → ') }}
          </div>
          <p class="assistant__draft-policy">{{ m.draft.cancelPolicy }}</p>
          <GButton type="primary" block :loading="placing" @click="confirmOrder(m.draft)">
            确认下单（{{ m.draft.seatCount }} 座 · 实付以收银台为准）
          </GButton>
        </div>
      </div>
    </div>

    <div class="assistant__input safe-bottom">
      <GInput
        v-model="text"
        placeholder="问问助手… 或说「订明天去邯郸的，两个人」"
        @keyup.enter="send"
      />
      <GButton type="primary" :loading="sending" @click="send">发送</GButton>
    </div>

    <GToast v-model="toast" :message="toastMsg" />
  </div>
</template>

<style scoped>
.assistant {
  position: relative;
  min-height: 100vh;
}

.assistant__list {
  padding: var(--sp-2);
  overflow-y: auto;
  height: calc(100vh - 44px - 76px);
}

.assistant__meta {
  margin: calc(-1 * var(--sp-1)) 0 var(--sp-2) 12%;
  font-size: var(--fs-12);
  color: var(--text-3);
}

/* F1 订单确认卡 */
.assistant__draft {
  margin: var(--sp-1) 12% var(--sp-2) 0;
  padding: var(--sp-2);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1);
}

.assistant__draft-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--sp-1);
}

.assistant__draft-route {
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
}

.assistant__draft-price {
  color: var(--c-orange-500);
  font-weight: var(--fw-semibold);
}

.assistant__draft-row--sub {
  margin-top: var(--sp-1);
  font-size: var(--fs-12);
  color: var(--text-2);
}

.assistant__draft-stops {
  margin-top: var(--sp-1);
  font-size: var(--fs-12);
  color: var(--brand);
}

.assistant__draft-policy {
  margin: var(--sp-1) 0;
  font-size: var(--fs-12);
  color: var(--text-3);
}

.assistant__input {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  gap: var(--sp-1);
  padding: var(--sp-1) var(--sp-2);
  background: color-mix(in srgb, var(--bg-base) 78%, transparent);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
  border-top: 1px solid var(--border);
}

.assistant__input .g-input {
  flex: 1;
}
</style>
