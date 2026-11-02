<script setup lang="ts">
/** H5 主应用：路由出口 + 顶部状态栏占位（NavBar 由各页自持，TabBar 在布局组件） */
import { RouterView } from 'vue-router'
</script>

<template>
  <div class="h5-app">
    <RouterView />
  </div>
</template>

<style scoped>
.h5-app {
  min-height: 100vh;
  background: var(--bg-base);
}

/* Glass 主题双光斑背景（单屏 ≤3 层） */
.h5-app::before {
  content: '';
  position: fixed;
  top: -20vh;
  left: -10vw;
  width: 60vw;
  height: 60vh;
  background: radial-gradient(circle, var(--bg-glow-1), transparent 70%);
  filter: blur(var(--blur));
  pointer-events: none;
  z-index: 0;
}

.h5-app::after {
  content: '';
  position: fixed;
  bottom: -25vh;
  right: -15vw;
  width: 55vw;
  height: 55vh;
  background: radial-gradient(circle, var(--bg-glow-2), transparent 70%);
  filter: blur(var(--blur));
  pointer-events: none;
  z-index: 0;
}
</style>
