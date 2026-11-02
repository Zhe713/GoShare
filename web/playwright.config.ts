import { defineConfig } from '@playwright/test'

/** GoShare H5 E2E：主链路（登录→语义搜索→下单→支付→订单）+ AI 助手问答 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    viewport: { width: 375, height: 812 },
    actionTimeout: 15_000,
    trace: 'retain-on-failure',
  },
  // vite dev server 已手动启动（5173），复用现有实例
  webServer: {
    command: 'echo reuse-existing',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 10_000,
  },
})
