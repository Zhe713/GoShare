<script setup lang="ts">
/** AI 出行助手：对话式（GChatBubble）+ 通用问答（省 token 四关卡演示，含缓存/规则标注） */
import { nextTick, ref } from 'vue'
import { useRouter } from 'vue-router'
import { GNavBar, GChatBubble, GInput, GButton } from '@/g-ui'
import { assistantChat } from '@/api/user'

const router = useRouter()

interface Msg {
  role: 'user' | 'assistant'
  text: string
  meta?: string
}

const messages = ref<Msg[]>([
  {
    role: 'assistant',
    text: '你好，我是 GoShare 出行助手。可以帮你：口语搜行程、算取消费用、看券包。试试问我「取消订单会扣钱吗」？',
  },
])
const text = ref('')
const sending = ref(false)
const listRef = ref<HTMLElement | null>(null)

async function send() {
  const q = text.value.trim()
  if (!q || sending.value) return
  messages.value.push({ role: 'user', text: q })
  text.value = ''
  sending.value = true
  try {
    const r = await assistantChat(q)
    const meta = r.fromCache
      ? '缓存命中（关卡1）· 零模型调用'
      : r.modelName.includes('规则')
        ? '规则小模型先答（关卡2）· 零成本'
        : r.modelName
    messages.value.push({ role: 'assistant', text: r.answer, meta })
  } catch (e) {
    messages.value.push({ role: 'assistant', text: (e as Error).message, meta: '服务暂不可用' })
  } finally {
    sending.value = false
    await nextTick()
    listRef.value?.scrollTo({ top: 99999, behavior: 'smooth' })
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
      </div>
    </div>

    <div class="assistant__input safe-bottom">
      <GInput
        v-model="text"
        placeholder="问问助手…"
        @keyup.enter="send"
      />
      <GButton type="primary" :loading="sending" @click="send">发送</GButton>
    </div>
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
  margin: -var(--sp-1) 0 var(--sp-2) 12%;
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
