<script setup lang="ts">
/** GCard：surface + 1px border + r-lg(20) + shadow-1（Glass 主题额外加 highlight） */
withDefaults(
  defineProps<{
    padding?: 'compact' | 'regular' | 'none'
  }>(),
  { padding: 'regular' },
)
</script>

<template>
  <div class="g-card" :class="`g-card--${padding}`">
    <slot />
  </div>
</template>

<style scoped>
.g-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1), var(--highlight);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
}

.g-card--regular {
  padding: var(--sp-2);
}

.g-card--compact {
  padding: var(--sp-1);
}

.g-card--none {
  padding: 0;
}
</style>
