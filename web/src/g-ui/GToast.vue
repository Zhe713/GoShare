<script setup lang="ts">
/** GToast：顶部浮层 r-md(12)，2.5s 消失，不遮挡导航 */
import { onMounted, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    message?: string
  }>(),
  { modelValue: false, message: '' },
)

const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>()
const visible = ref(props.modelValue)

onMounted(() => {
  visible.value = props.modelValue
})

function dismiss() {
  visible.value = false
  emit('update:modelValue', false)
}

// 打开后自动 2.5s 消失
import { watch } from 'vue'
watch(
  () => props.modelValue,
  (v) => {
    visible.value = v
    if (v) {
      setTimeout(dismiss, 2500)
    }
  },
)
</script>

<template>
  <Teleport to="body">
    <Transition name="g-toast">
      <div v-if="visible" class="g-toast" role="status">
        <span class="g-toast__icon" aria-hidden="true">✓</span>
        <span class="g-toast__msg">{{ message }}</span>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.g-toast {
  position: fixed;
  top: calc(44px + var(--sp-2));
  left: 50%;
  transform: translateX(-50%);
  z-index: 120;
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  padding: var(--sp-1) var(--sp-2);
  background: var(--surface-strong);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-2);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
  color: var(--text-1);
  font-size: var(--fs-14);
}

.g-toast__icon {
  color: var(--c-green-500);
  font-weight: var(--fw-semibold);
}

.g-toast-enter-active,
.g-toast-leave-active {
  transition: opacity var(--dur-fast), transform var(--dur-fast);
}

.g-toast-enter-from,
.g-toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-8px);
}
</style>
