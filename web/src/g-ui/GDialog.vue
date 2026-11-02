<script setup lang="ts">
/** GDialog：居中 r-lg(20)，宽 80%；移动端按钮上下排列 */
withDefaults(
  defineProps<{
    modelValue?: boolean
    title?: string
    message?: string
    confirmText?: string
    cancelText?: string
  }>(),
  { modelValue: false, title: '', message: '', confirmText: '确定', cancelText: '取消' },
)

const emit = defineEmits<{ 'update:modelValue': [v: boolean]; confirm: []; cancel: [] }>()

function close() {
  emit('update:modelValue', false)
  emit('cancel')
}
function ok() {
  emit('update:modelValue', false)
  emit('confirm')
}
</script>

<template>
  <Teleport to="body">
    <Transition name="g-dialog">
      <div v-if="modelValue" class="g-dialog">
        <div class="g-dialog__mask" @click="close"></div>
        <div class="g-dialog__box" role="dialog" aria-modal="true">
          <div v-if="title" class="g-dialog__title">{{ title }}</div>
          <div class="g-dialog__msg">{{ message }}</div>
          <div class="g-dialog__body"><slot /></div>
          <div class="g-dialog__actions">
            <button class="g-dialog__btn g-dialog__btn--cancel" @click="close">{{ cancelText }}</button>
            <button class="g-dialog__btn g-dialog__btn--confirm" @click="ok">{{ confirmText }}</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.g-dialog {
  position: fixed;
  inset: 0;
  z-index: 110;
  display: flex;
  align-items: center;
  justify-content: center;
}

.g-dialog__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.g-dialog__box {
  position: relative;
  width: 80%;
  background: var(--bg-base);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-3);
  padding: var(--sp-3) var(--sp-2) var(--sp-2);
}

.g-dialog__title {
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
  text-align: center;
}

.g-dialog__msg {
  margin-top: var(--sp-1);
  font-size: var(--fs-14);
  color: var(--text-2);
  text-align: center;
  line-height: var(--lh-base);
}

.g-dialog__actions {
  display: flex;
  gap: var(--sp-1);
  margin-top: var(--sp-3);
}

/* 移动端按钮上下排列 */
@media (width <= 430px) {
  .g-dialog__actions {
    flex-direction: column;
  }
}

.g-dialog__btn {
  flex: 1;
  min-height: 44px;
  border-radius: var(--r-md);
  font-size: var(--fs-16);
}

.g-dialog__btn--cancel {
  color: var(--text-1);
  background: var(--surface);
  border: 1px solid var(--border);
}

.g-dialog__btn--confirm {
  color: var(--on-brand);
  background: var(--brand-grad);
}

.g-dialog-enter-active,
.g-dialog-leave-active {
  transition: opacity var(--dur-base);
}

.g-dialog-enter-active .g-dialog__box,
.g-dialog-leave-active .g-dialog__box {
  transition: transform var(--dur-base) var(--ease-out-expo);
}

.g-dialog-enter-from,
.g-dialog-leave-to {
  opacity: 0;
}

.g-dialog-enter-from .g-dialog__box,
.g-dialog-leave-to .g-dialog__box {
  transform: scale(0.96);
}
</style>
