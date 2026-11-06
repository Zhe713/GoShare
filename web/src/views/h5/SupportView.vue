<script setup lang="ts">
/** 联系客服：按订单号创建工单，AI 生成会话摘要 + 回复草稿（B1 客服助手） */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GCard, GButton, GInput, GToast, GSkeleton } from '@/g-ui'
import { apiPost } from '@/api/request'

const router = useRouter()
const orderNo = ref('')
const submitting = ref(false)
const toast = ref(false)
const toastMsg = ref('')

interface WorkOrderResult {
  workOrderId: number
  orderNo: string
  category: string
  status: string
  summary: string
  replyDraft: string
  messageCount: number
  firstTime: boolean
}
const result = ref<WorkOrderResult | null>(null)

/** 创建演示工单（B1 mock：内置会话消息 → AI 摘要 + 回复草稿） */
async function createTicket() {
  if (!orderNo.value.trim() || submitting.value) return
  submitting.value = true
  result.value = null
  try {
    result.value = await apiPost<WorkOrderResult>('/assistant/work-order/mock', {
      orderNo: orderNo.value.trim(),
      messages: [
        '我的订单一直显示待支付，但是钱已经扣了',
        '客服你好，我这边银行流水显示扣款成功，订单还是待支付，怎么办',
        '已经等了十分钟了，急',
      ],
    })
    toastMsg.value = '工单已创建，AI 摘要与回复草稿已生成'
    toast.value = true
  } catch (e) {
    toastMsg.value = (e as Error).message
    toast.value = true
  } finally {
    submitting.value = false
  }
}

const CATEGORY_TEXT: Record<string, string> = {
  退款: '退款问题',
  改期: '改期问题',
  候补: '候补问题',
  其他: '其他问题',
}
</script>

<template>
  <div class="support">
    <GNavBar title="联系客服" @back="router.back()" />

    <GCard class="support__card">
      <h3 class="support__section">创建工单</h3>
      <GInput v-model="orderNo" label="关联订单号" placeholder="填写订单号，如 20261105162945453760" />
      <p class="support__hint">提交后 AI 会自动打标问题分类、生成会话摘要与客服回复草稿（B1 客服助手演示）。</p>
      <GButton type="primary" block :loading="submitting" @click="createTicket">提交工单</GButton>
    </GCard>

    <GSkeleton v-if="submitting && !result" :rows="4" :height="14" />

    <GCard v-if="result" class="support__card">
      <div class="support__head">
        <span class="support__id">工单 #{{ result.workOrderId }}</span>
        <span class="support__tag">{{ CATEGORY_TEXT[result.category] ?? result.category }}</span>
        <span class="support__status">{{ result.status }}</span>
      </div>
      <p class="support__label">AI 会话摘要（{{ result.messageCount }} 条消息）</p>
      <p class="support__summary">{{ result.summary }}</p>
      <p class="support__label">回复草稿（客服可编辑后发送）</p>
      <p class="support__reply">{{ result.replyDraft }}</p>
    </GCard>

    <GToast v-model="toast" :message="toastMsg" />
  </div>
</template>

<style scoped>
.support {
  min-height: 100vh;
  padding-bottom: 24px;
}

.support__card {
  margin: var(--sp-2);
}

.support__section {
  font-size: var(--fs-14);
  font-weight: var(--fw-semibold);
  margin-bottom: var(--sp-2);
}

.support__hint {
  margin: var(--sp-1) 0 var(--sp-2);
  font-size: var(--fs-12);
  color: var(--text-3);
}

.support__head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}

.support__id {
  font-size: var(--fs-14);
  font-weight: var(--fw-semibold);
}

.support__tag {
  font-size: var(--fs-12);
  color: var(--brand);
  background: color-mix(in srgb, var(--brand) 10%, transparent);
  padding: 2px 8px;
  border-radius: var(--r-sm);
}

.support__status {
  font-size: var(--fs-12);
  color: var(--text-3);
}

.support__label {
  margin: var(--sp-2) 0 var(--sp-1);
  font-size: var(--fs-12);
  color: var(--text-3);
}

.support__summary,
.support__reply {
  font-size: var(--fs-13);
  color: var(--text-1);
  line-height: 1.7;
}

.support__reply {
  background: color-mix(in srgb, var(--brand) 6%, transparent);
  padding: var(--sp-2);
  border-radius: var(--r-md);
}
</style>
