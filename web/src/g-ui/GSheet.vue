<script setup lang="ts">
/** GSheet：底部抽屉 r-xl(28) + 拖动把手（40×4 圆角 2）；320ms 位移 + 遮罩淡入 */
defineProps<{
  modelValue?: boolean
  title?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>()
</script>

<template>
  <Teleport to="body">
    <Transition name="g-sheet">
      <div v-if="modelValue" class="g-sheet">
        <div class="g-sheet__mask" @click="emit('update:modelValue', false)"></div>
        <div class="g-sheet__panel safe-bottom" role="dialog" aria-modal="true">
          <div class="g-sheet__handle"></div>
          <div v-if="title" class="g-sheet__title">{{ title }}</div>
          <div class="g-sheet__body"><slot /></div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.g-sheet {
  position: fixed;
  inset: 0;
  z-index: 100;
}

.g-sheet__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.g-sheet__panel {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--bg-base);
  border-radius: var(--r-xl) var(--r-xl) 0 0;
  box-shadow: var(--shadow-3);
  padding: var(--sp-1) var(--sp-2) var(--sp-3);
}

.g-sheet__handle {
  width: 40px;
  height: 4px;
  margin: var(--sp-1) auto;
  border-radius: var(--r-xs);
  background: var(--text-3);
}

.g-sheet__title {
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
  text-align: center;
  margin-bottom: var(--sp-2);
}

.g-sheet__body {
  max-height: 60vh;
  overflow-y: auto;
}

/* 320ms ease-out-expo：位移 + 遮罩淡入 */
.g-sheet-enter-active,
.g-sheet-leave-active {
  transition: opacity var(--dur-base);
}

.g-sheet-enter-active .g-sheet__panel,
.g-sheet-leave-active .g-sheet__panel {
  transition: transform 320ms var(--ease-out-expo);
}

.g-sheet-enter-from,
.g-sheet-leave-to {
  opacity: 0;
}

.g-sheet-enter-from .g-sheet__panel,
.g-sheet-leave-to .g-sheet__panel {
  transform: translateY(100%);
}
</style>
