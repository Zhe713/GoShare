<script setup lang="ts">
/** GChatBubble：用户=品牌渐变实色，助手=surface 玻璃；最大宽 78% */
withDefaults(
  defineProps<{
    role?: 'user' | 'assistant'
    text?: string
  }>(),
  { role: 'assistant', text: '' },
)
</script>

<template>
  <div class="g-chat" :class="`g-chat--${role}`">
    <div class="g-chat__bubble">{{ text }}<slot /></div>
  </div>
</template>

<style scoped>
.g-chat {
  display: flex;
  width: 100%;
  margin-bottom: var(--sp-2);
}
.g-chat--assistant {
  justify-content: flex-start;
}
.g-chat--user {
  justify-content: flex-end;
}
.g-chat__bubble {
  max-width: 78%;
  padding: var(--sp-2);
  border-radius: var(--r-md);
  font-size: var(--fs-14);
  line-height: var(--lh-base);
  word-break: break-word;
}
.g-chat--assistant .g-chat__bubble {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md) var(--r-md) var(--r-md) var(--r-sm);
  color: var(--text-1);
  box-shadow: var(--shadow-1), var(--highlight);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
}
.g-chat--user .g-chat__bubble {
  background: var(--brand-grad);
  border-radius: var(--r-md) var(--r-md) var(--r-sm) var(--r-md);
  color: var(--on-brand);
}
</style>
