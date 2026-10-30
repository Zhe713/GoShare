<script setup lang="ts">
/** GSkeleton：骨架块 r-sm(8)，微光扫过 1.6s 循环（唯一允许的循环动画） */
withDefaults(
  defineProps<{
    rows?: number
    /** 每行高度（px） */
    height?: number
  }>(),
  { rows: 3, height: 14 },
)
</script>

<template>
  <div class="g-skeleton" role="status" aria-label="加载中">
    <div
      v-for="i in rows"
      :key="i"
      class="g-skeleton__row"
      :style="{ height: height + 'px' }"
      :class="{ 'g-skeleton__row--short': i === rows }"
    ></div>
  </div>
</template>

<style scoped>
.g-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.g-skeleton__row {
  border-radius: var(--r-sm);
  background: var(--surface);
  position: relative;
  overflow: hidden;
}
.g-skeleton__row--short {
  width: 60%;
}
/* 微光扫过 1.6s 循环（唯一允许的循环动画） */
.g-skeleton__row::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    transparent,
    color-mix(in srgb, var(--text-1) 8%, transparent),
    transparent
  );
  transform: translateX(-100%);
  animation: g-skeleton-shine 1.6s linear infinite;
}
@keyframes g-skeleton-shine {
  100% {
    transform: translateX(100%);
  }
}
</style>
