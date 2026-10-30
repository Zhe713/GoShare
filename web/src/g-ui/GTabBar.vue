<script setup lang="ts">
/** GTabBar：高 56 + 安全区；选中态图标与文字同步变色；图标为内置线性 SVG（同一描边体系） */
withDefaults(
  defineProps<{
    modelValue?: string
    tabs: { key: string; label: string; icon: string }[]
  }>(),
  { modelValue: '' },
)

const emit = defineEmits<{ 'update:modelValue': [v: string] }>()

/** 统一线性图标（stroke=currentColor，视觉重心一致，24×24 视窗） */
const ICONS: Record<string, string> = {
  home: 'M3.5 10.5 L12 3.5 L20.5 10.5 M5.5 9.5 V20.5 H9.5 V14.5 H14.5 V20.5 H18.5 V9.5',
  search: 'M10.5 4.5 a6 6 0 1 0 0.1 12 A6 6 0 0 0 10.5 4.5 M15.5 15.5 L20.5 20.5',
  coupons: 'M3 7.5 V6 H21 V7.5 M3 9 V18 H21 V9 M12 9 V18',
  mine: 'M12 12.5 a4.2 4.2 0 1 0 0 -8.4 a4.2 4.2 0 0 0 0 8.4 M4.5 20.5 c0-4 3.4-6 7.5-6 s7.5 2 7.5 6',
}
</script>

<template>
  <nav class="g-tabbar safe-bottom">
    <button
      v-for="t in tabs"
      :key="t.key"
      class="g-tabbar__item"
      :class="{ 'g-tabbar__item--active': modelValue === t.key }"
      @click="emit('update:modelValue', t.key)"
    >
      <svg class="g-tabbar__icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path :d="ICONS[t.icon] || t.icon" />
      </svg>
      <span class="g-tabbar__label">{{ t.label }}</span>
    </button>
  </nav>
</template>

<style scoped>
.g-tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  display: flex;
  height: 56px;
  background: color-mix(in srgb, var(--bg-base) 78%, transparent);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
  border-top: 1px solid var(--border);
}
.g-tabbar__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: var(--text-3);
  transition: color var(--dur-fast);
}
.g-tabbar__icon {
  line-height: 1;
}
.g-tabbar__label {
  font-size: var(--fs-12);
}
.g-tabbar__item--active {
  color: var(--brand);
  font-weight: var(--fw-medium);
}
</style>
