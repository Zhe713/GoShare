# GoShare 前端阶段 6 交付与 20 条精致度验收

> 执行依据：`docs/GoShare-前端视觉规范.md`（v2）第十二、十三节；工程：`web\`
> 验证方式：`npm run build`（vue-tsc strict + vite）→ `npm run dev` 经 proxy 联调后端 → `npx vitest run` → `npx storybook build`

## 一、交付范围

| 模块 | 内容 | 状态 |
|---|---|---|
| 设计体系 | L1 基础 token + L2 三主题语义 token（Glass/Minimal/Aurora）+ reset（字号七档/等宽数字/安全区/reduced-motion/低端降级） | ✅ |
| g-ui 组件 | 14 个：GButton/GCard/GInput/GNavBar/GTabBar/GSheet/GDialog/GToast/GSkeleton/GEmpty/GError/GTripCard/GCouponCard/GChatBubble，统一 `src/g-ui/index.ts` 出口 | ✅ |
| H5（Glass） | 8 页：首页/搜索/行程详情/订单/支付/券包/我的/AI 助手，接真实接口（Gateway :8000 经 vite proxy） | ✅ 联调通过 |
| 管理后台（Minimal） | 行程管理/ AI 助手测试/帮助，真实接口数据 | ✅ |
| 数据大屏（Aurora） | ECharts 价格分布（真实行程数据）/ 信任分布 / AI 调用缓存命中率（真实 llm-stats） | ✅ |
| Storybook | 14 组件 stories + 三主题预览背景 + addon-essentials | ✅ |
| 前端单测 | Vitest 20 用例全绿（utils 9 + 组件 11） | ✅ 20/20 |

## 二、联调实录（vite proxy 5173 → gateway 8000）

| 链路 | 请求 | 结果 |
|---|---|---|
| 登录 | POST /api/user/login | 200，token 151 位 |
| 首页行程 | GET /api/trip/search?fromCity=石家庄&toCity=北京 | 200，3 条 |
| 语义搜索(A1) | POST /api/assistant/semantic-search「周五18点从石家庄去邯郸，能带大件」 | 200，结构化回显 + 2 条（top 0.94，reasons 解释） |
| 助手问答(关卡) | POST /api/assistant/chat「取消订单会扣钱吗」 | 200，规则小模型命中，零成本 |
| AI 统计 | GET /api/assistant/llm-stats | 200，cacheHitRate 46.7%，gateSkippedRate 20% |
| 下单闭环 | trip/5 详情 → order/create → 支付 | CREATED → SUCCESS 全通 |
| 券包 | coupon/mine 3 张；coupon/receive 领券（业务幂等拦截正确） | 200 |

> 说明：后端无「券模板列表」接口，领券中心模板展示取库中真实初始化模板（id 1/2/3）；列表/排序/金额全部来自后端真实接口与代码计算，不依赖 LLM。

## 三、20 条精致度验收逐条打勾

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 页面没有裸色值/裸圆角/裸阴影 | ✅ | 视图层全部 `var(--*)`；组件内品牌渐变前景统一 `--on-brand`；ECharts 色值属图表配置，页面 CSS 层零裸值 |
| 2 | 全屏只有 1 主色 + 1 强调色 | ✅ | 品牌蓝主 + 紫强调（brand-grad），红/绿/橙仅作语义状态 |
| 3 | 字号只出现 32/24/20/16/14/13/12 | ✅ | `--fs-*` 七档唯一来源，无裸字号 |
| 4 | 间距全部是 8 的倍数 | ✅ | `--sp-*` 8pt 节奏；2px 仅用于描边/把手等细节尺寸 |
| 5 | 圆角只用 8/12/20/28 | ✅ | `--r-sm/md/lg/xl`；`50%` 仅圆形元素（spinner/撕口圆点） |
| 6 | 阴影只用三级 | ✅ | `--shadow-1/2/3` + 玻璃高光边 |
| 7 | 所有按钮都有四态 | ✅ | GButton：默认/按下 scale.97/禁用/加载 spinner |
| 8 | 所有列表都有 骨架/空/错误 三态 | ✅ | GSkeleton/GEmpty/GError 三件套；首页/搜索/后台均已接三态 |
| 9 | 所有表单都有校验提示与禁用态 | ✅ | GInput error/disabled + label |
| 10 | 金额/倒计时为等宽数字且右对齐 | ✅ | `.num`（tabular-nums）；金额右对齐（meta 行 `margin-left:auto`） |
| 11 | 图标同一套、同一描边、同一视觉重心 | ✅ | 内置 8 个线性 SVG（24 视窗、stroke 1.8、round），GTabBar/搜索入口统一 |
| 12 | 点击区 ≥44px | ✅ | 按钮 44、TabBar 项 56/4、返回 44、座位钮 44、空/错操作钮 44 |
| 13 | 长文本有截断策略 | ✅ | GCouponCard 名称 ellipsis；GChatBubble break-word |
| 14 | 图片有占位与懒加载 | ✅ | 全站无位图，图标全内联 SVG，无加载缺口 |
| 15 | 页面转场与抽屉动效曲线一致 | ✅ | 320ms ease-out-expo（GSheet）；Dialog/Toast 同曲线；按钮 160ms |
| 16 | 无无限循环动画（骨架除外） | ✅ | GSkeleton 微光 1.6s 唯一循环；spinner 为加载态指示 |
| 17 | 深色主题文字对比度达标 | ✅ | Glass/Aurora 文字 .95/.66/.42 白阶，最小 3:1 以上 |
| 18 | 低端机降级可用 | ✅ | `@supports not (backdrop-filter)` → 浮层退半透明纯色 |
| 19 | 首屏 JS ≤180KB，LCP ≤2.5s | ⚠️ 部分 | H5 入口 gzip 33KB（远低于 180KB）；LCP 需真实浏览器实测（本地 dev 已可跑） |
| 20 | Storybook 里 14 组件全状态可预览 | ✅ | 3 个 stories 文件覆盖 14 组件全状态，三主题背景可切 |

**未达/待办**：第 19 条 LCP 数值留待浏览器 Performance 实测后回填；Stylelint 裸样式门禁与 Playwright 截图对比（视觉回归）属阶段 7 测试收口。

## 四、运行方式

```powershell
cd D:\GoShare\web
npm run dev        # http://localhost:5173/（H5）/admin.html（后台）/screen.html（大屏）
npm run build      # 产物 dist/（三入口）
npx vitest run     # 20/20
npx storybook dev  # http://localhost:6006/
```

## 五、简历讲点（面试可用）

> 「自建前端设计体系：双级 design token（L1 基础 → L2 语义，`data-theme` 一键切 Glass/Minimal/Aurora 三主题）；14 个统一组件（按钮四态、列表三态、券三态），Storybook 文档 + 20 条精致度验收；移动端玻璃层数 ≤3、低端机 backdrop-filter 降级、首屏 JS gzip 33KB（预算 180KB）；AI 助手页直接展示 LlmGateway 省 token 四关卡——缓存命中率 46.7%、规则小模型零成本拦截，统计来自真实 llm-stats。」
