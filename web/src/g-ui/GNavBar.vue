<script setup lang="ts">
/** GNavBar：高 44 + 安全区；Glass 半透明，Minimal 纯白 + 底部 1px 8% 分割 */
withDefaults(
  defineProps<{
    title?: string
    showBack?: boolean
  }>(),
  { title: '', showBack: true },
)

const emit = defineEmits<{ back: [] }>()
</script>

<template>
  <header class="g-navbar">
    <div class="g-navbar__inner">
      <button v-if="showBack" class="g-navbar__back" aria-label="返回" @click="emit('back')">
        <span class="g-navbar__arrow"></span>
      </button>
      <div class="g-navbar__title">{{ title }}</div>
      <div class="g-navbar__slot"><slot name="right" /></div>
    </div>
  </header>
</template>

<style scoped>
.g-navbar {
  position: sticky;
  top: 0;
  z-index: 10;
  height: 44px;
  background: color-mix(in srgb, var(--bg-base) 70%, transparent);
  backdrop-filter: blur(var(--blur)) saturate(var(--saturate));
  border-bottom: 1px solid var(--border);
}

.g-navbar__inner {
  position: relative;
  display: flex;
  align-items: center;
  height: 100%;
  padding: 0 var(--sp-2);
}

.g-navbar__back {
  position: absolute;
  left: var(--sp-1);
  top: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  color: var(--text-1);
}

.g-navbar__arrow {
  width: 10px;
  height: 10px;
  border-left: 2px solid currentcolor;
  border-bottom: 2px solid currentcolor;
  transform: rotate(45deg);
}

.g-navbar__title {
  flex: 1;
  text-align: center;
  font-size: var(--fs-16);
  font-weight: var(--fw-semibold);
  color: var(--text-1);
}

.g-navbar__slot {
  margin-left: auto;
}
</style>
