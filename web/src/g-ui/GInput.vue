<script setup lang="ts">
/** GInput：高 44/48，聚焦 2px 品牌描边（不用发光）；含禁用/校验提示/清空 */
import { computed, ref, useId } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    type?: 'text' | 'password' | 'number'
    placeholder?: string
    label?: string
    error?: string
    disabled?: boolean
    size?: 'normal' | 'large'
  }>(),
  { modelValue: '', type: 'text', placeholder: '', label: '', error: '', disabled: false, size: 'normal' },
)

const emit = defineEmits<{ 'update:modelValue': [v: string] }>()
const id = useId()
const focused = ref(false)

const cls = computed(() => {
  const states = [
    focused.value && 'g-input--focus',
    props.error && 'g-input--error',
    props.disabled && 'g-input--disabled',
    props.size === 'large' && 'g-input--large',
  ]
  return ['g-input', ...states.filter(Boolean)].join(' ')
})

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLInputElement).value)
}
</script>

<template>
  <div class="g-input-wrap">
    <label v-if="label" :for="id" class="g-input__label">{{ label }}</label>
    <div :class="cls">
      <input
        :id="id"
        :type="type"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        @input="onInput"
        @focus="focused = true"
        @blur="focused = false"
      />
    </div>
    <p v-if="error" class="g-input__error">{{ error }}</p>
  </div>
</template>

<style scoped>
.g-input-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.g-input__label {
  font-size: var(--fs-13);
  color: var(--text-2);
}
.g-input {
  display: flex;
  align-items: center;
  height: 44px;
  padding: 0 var(--sp-2);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  transition: border-color var(--dur-fast);
}
.g-input--large {
  height: 48px;
}
.g-input input {
  flex: 1;
  font-size: var(--fs-16);
  color: var(--text-1);
  background: none;
}
.g-input input::placeholder {
  color: var(--text-3);
}
/* 聚焦：2px 品牌描边（非发光） */
.g-input--focus {
  border: 2px solid var(--brand);
  padding: 0 calc(var(--sp-2) - 1px);
}
.g-input--error {
  border-color: var(--c-red-500);
}
.g-input--disabled {
  opacity: 0.45;
  pointer-events: none;
}
.g-input__error {
  font-size: var(--fs-12);
  color: var(--c-red-500);
}
</style>
