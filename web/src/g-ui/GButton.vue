<script setup lang="ts">
/** GButton：三型（主/次/文字）× 四态（默认/按下/禁用/加载）
 *  主按钮品牌渐变；按下 scale 0.97（160ms ease-out-expo）；最小高度 44px
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    type?: 'primary' | 'secondary' | 'text'
    disabled?: boolean
    loading?: boolean
    block?: boolean
  }>(),
  { type: 'primary', disabled: false, loading: false, block: false },
)

const emit = defineEmits<{ click: [e: MouseEvent] }>()

const cls = computed(() => {
  const base = 'g-btn'
  const state = props.disabled ? 'disabled' : props.loading ? 'loading' : ''
  return [base, `g-btn--${props.type}`, state && `g-btn--${state}`, props.block && 'g-btn--block']
    .filter(Boolean)
    .join(' ')
})

function onClick(e: MouseEvent) {
  if (props.disabled || props.loading) return
  emit('click', e)
}
</script>

<template>
  <button :class="cls" :disabled="disabled || loading" @click="onClick">
    <span v-if="loading" class="g-btn__spinner" aria-hidden="true"></span>
    <span class="g-btn__label"><slot /></span>
  </button>
</template>

<style scoped>
.g-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--sp-1);
  min-height: 44px;
  padding: 0 var(--sp-3);
  border-radius: var(--r-md);
  font-size: var(--fs-16);
  font-weight: var(--fw-medium);
  transition: transform var(--dur-fast) var(--ease-out-expo), background var(--dur-fast), opacity var(--dur-fast);
  user-select: none;
  -webkit-tap-highlight-color: transparent;
}

.g-btn:not(:disabled):active {
  transform: scale(0.97);
}

.g-btn--block {
  display: flex;
  width: 100%;
}

.g-btn__spinner {
  width: 16px;
  height: 16px;
  border: 2px solid currentcolor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: g-btn-spin 0.8s linear infinite;
}

@keyframes g-btn-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 主按钮：品牌渐变 */
.g-btn--primary {
  color: var(--on-brand);
  background: var(--brand-grad);
  box-shadow: var(--shadow-1);
}

.g-btn--primary:not(:disabled):active {
  background: linear-gradient(135deg, var(--c-blue-600), var(--c-violet-500));
}

/* 次按钮：surface + 边框 */
.g-btn--secondary {
  color: var(--text-1);
  background: var(--surface);
  border: 1px solid var(--border);
  box-shadow: var(--highlight);
}

/* 文字按钮 */
.g-btn--text {
  color: var(--brand);
  background: none;
  min-height: 44px;
}

/* 禁用：降透明度 + 禁点 */
.g-btn--disabled {
  opacity: 0.4;
  cursor: not-allowed;
  pointer-events: none;
}

/* 加载态 */
.g-btn--loading {
  opacity: 0.7;
  pointer-events: none;
}
</style>
