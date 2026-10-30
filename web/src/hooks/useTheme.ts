import { ref, watchEffect } from 'vue'

export type ThemeName = 'glass' | 'minimal' | 'aurora'

const theme = ref<ThemeName>((localStorage.getItem('goshare-theme') as ThemeName) || 'glass')

/** 主题切换：写根节点 data-theme + localStorage（防闪烁的内联脚本已在入口 html） */
export function useTheme() {
  watchEffect(() => {
    document.documentElement.setAttribute('data-theme', theme.value)
    try {
      localStorage.setItem('goshare-theme', theme.value)
    } catch (e) {
      /* ignore */
    }
  })
  return { theme, setTheme: (t: ThemeName) => (theme.value = t) }
}
