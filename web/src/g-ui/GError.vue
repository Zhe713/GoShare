<script setup lang="ts">
/** GError：图标 + 原因 + 重试按钮（网络/服务/权限三类文案调用方给） */
withDefaults(
  defineProps<{
    message?: string
  }>(),
  { message: '网络开了小差，请检查后重试' },
)

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="g-error">
    <svg class="g-error__icon" viewBox="0 0 64 64" width="64" height="64" aria-hidden="true">
      <circle cx="32" cy="32" r="26" fill="none" stroke="var(--c-red-500)" stroke-width="2.5" opacity="0.55" />
      <path d="M32 20 L32 36" stroke="var(--c-red-500)" stroke-width="3" stroke-linecap="round" />
      <circle cx="32" cy="44" r="2.6" fill="var(--c-red-500)" />
    </svg>
    <p class="g-error__msg">{{ message }}</p>
    <button class="g-error__retry" @click="emit('retry')">重试</button>
  </div>
</template>

<style scoped>
.g-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-4) var(--sp-2);
  text-align: center;
}

.g-error__msg {
  font-size: var(--fs-14);
  color: var(--text-2);
}

.g-error__retry {
  min-height: 44px;
  padding: 0 var(--sp-3);
  color: var(--text-1);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  font-size: var(--fs-14);
  box-shadow: var(--shadow-1);
}

.g-error__retry:active {
  transform: scale(0.97);
  transition: transform var(--dur-fast) var(--ease-out-expo);
}
</style>
