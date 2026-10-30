import type { Preview } from '@storybook/vue3'
import '../src/design/tokens.css'
import '../src/design/theme.css'
import '../src/design/reset.css'

/** Storybook 预览：默认 Glass 主题，工具栏可切 Minimal/Aurora（三主题视觉回归基线） */
const preview: Preview = {
  parameters: {
    backgrounds: { disable: true },
    controls: { expanded: true },
  },
  decorators: [
    (story) => ({
      template: `
        <div style="padding:24px;min-height:100vh;background:var(--bg-base);font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif">
          <story />
        </div>`,
    }),
  ],
}

export default preview
