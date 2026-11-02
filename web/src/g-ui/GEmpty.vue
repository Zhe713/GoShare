<script setup lang="ts">
/** GEmpty：线性插画 + 一句解释 + 一个可操作按钮（禁止只有"暂无数据"） */
withDefaults(
  defineProps<{
    text?: string
    actionText?: string
  }>(),
  { text: '这里还没有内容', actionText: '' },
)

const emit = defineEmits<{ action: [] }>()
</script>

<template>
  <div class="g-empty">
    <svg class="g-empty__art" viewBox="0 0 120 100" width="120" height="100" aria-hidden="true">
      <circle cx="60" cy="46" r="30" fill="none" stroke="var(--text-3)" stroke-width="2" stroke-dasharray="4 5" />
      <path d="M40 82 L50 66 L70 66 L80 82 Z" fill="none" stroke="var(--text-3)" stroke-width="2" stroke-linejoin="round" />
      <circle cx="60" cy="46" r="8" fill="var(--text-3)" opacity="0.5" />
      <path d="M20 60 Q12 52 20 44" fill="none" stroke="var(--text-3)" stroke-width="2" stroke-linecap="round" />
      <path d="M100 60 Q108 52 100 44" fill="none" stroke="var(--text-3)" stroke-width="2" stroke-linecap="round" />
    </svg>
    <p class="g-empty__text">{{ text }}</p>
    <button v-if="actionText" class="g-empty__action" @click="emit('action')">{{ actionText }}</button>
  </div>
</template>

<style scoped>
.g-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-4) var(--sp-2);
  text-align: center;
}

.g-empty__text {
  font-size: var(--fs-14);
  color: var(--text-2);
}

.g-empty__action {
  min-height: 44px;
  padding: 0 var(--sp-3);
  color: var(--on-brand);
  background: var(--brand-grad);
  border-radius: var(--r-md);
  font-size: var(--fs-14);
  box-shadow: var(--shadow-1);
}

.g-empty__action:active {
  transform: scale(0.97);
  transition: transform var(--dur-fast) var(--ease-out-expo);
}
</style>
