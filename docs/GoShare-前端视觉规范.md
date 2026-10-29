# GoShare 前端设计体系与视觉规范（v2）

> 目标：**精致、不廉价**。玻璃拟态只是可选主题之一，真正决定"贵不贵"的是**克制 + 节奏 + 一致 + 状态完整**。
> 覆盖：三套主题 / 双级 token / 排版 / 色彩 / 空间 / 14 个组件 / 动效 / 状态设计 / 适配 / 可访问性 / 性能预算 / 工程生态 / 验收清单。
> 适用：H5（Vue3 + Vant）、管理后台（Vue3 + Element Plus）、数据大屏。

---

## 一、精致感的定义（先统一品味基线）

"廉价"通常来自四件事：**颜色太多、圆角/阴影不统一、留白不够、状态缺失**。
"精致"来自四件事：**克制的用色、严格的 8pt 节奏、统一的组件语汇、每个状态都被设计过**。

三条铁律：
1. **一个界面不超过 1 个主色 + 1 个强调色**（其余用中性灰阶与透明度）
2. **所有组件只允许使用 token**，禁止页面里写裸色值/裸圆角/裸阴影
3. **任何交互都必须有完整四态**：默认 / 按下 / 禁用 / 加载；列表还要有 空 / 错误 / 骨架

---

## 二、三套主题（可一键切换，玻璃是其中之一）

| 主题 | 视觉 | 适合 | 实现要点 |
|---|---|---|---|
| **A. Glass 玻璃** | 深色底 + 双光斑 + 玻璃浮层 | H5 演示、答辩冲击力 | `backdrop-filter` + 半透明高光边；单屏 ≤3 层 |
| **B. Minimal 极简柔和** | 浅灰底 + 纯白卡片 + 大圆角 + 柔和阴影，**无模糊** | H5 日常、管理后台（最稳、最"贵"） | 白卡 + `0 2px 8px rgba(16,24,40,.06)` + 1px 极浅边 |
| **C. Aurora 深色** | 深色渐变 + 发光数字，无玻璃 | 数据大屏 | 深底 + 语义发光色 + 等宽数字 |

切换机制：根节点 `data-theme="glass|minimal|aurora"`，组件只读 token，不感知主题。
**默认策略**：H5 用 Glass（可一键切 Minimal），后台用 Minimal（浅色），大屏用 Aurora。

---

## 三、Design Tokens（两级：基础 → 语义）

```css
:root{
  /* ===== L1 基础 ===== */
  --c-blue-500:#4f8cff; --c-blue-600:#2f6bff; --c-violet-500:#7c5cff;
  --c-green-500:#2fbf71; --c-orange-500:#f5a623; --c-red-500:#ff5a5f;
  --n-0:#ffffff; --n-50:#f7f8fa; --n-100:#eef2f7; --n-400:#98a2b3;
  --n-600:#475467; --n-800:#1d2939; --n-900:#101828;
  --alpha-04:rgba(255,255,255,.04); --alpha-08:rgba(255,255,255,.08);
  --alpha-14:rgba(255,255,255,.14); --alpha-22:rgba(255,255,255,.22);

  --fs-12:12px; --fs-13:13px; --fs-14:14px; --fs-16:16px;
  --fs-20:20px; --fs-24:24px; --fs-32:32px;
  --lh-tight:1.25; --lh-base:1.55;
  --fw-regular:400; --fw-medium:500; --fw-semibold:600;

  --sp-1:8px; --sp-2:16px; --sp-3:24px; --sp-4:32px; --sp-5:40px;
  --r-sm:8px; --r-md:12px; --r-lg:20px; --r-xl:28px;

  --dur-fast:160ms; --dur-base:240ms; --dur-slow:420ms;
  --ease-out-expo:cubic-bezier(.16,1,.3,1);

  /* ===== L2 语义（主题相关，默认 Glass） ===== */
  --bg-base:#0b1020;
  --bg-glow-1:rgba(64,158,255,.35); --bg-glow-2:rgba(255,94,196,.22);
  --surface:var(--alpha-08); --surface-strong:var(--alpha-14);
  --border:var(--alpha-22);
  --text-1:rgba(255,255,255,.95); --text-2:rgba(255,255,255,.66); --text-3:rgba(255,255,255,.42);
  --brand:var(--c-blue-500); --brand-grad:linear-gradient(135deg,#4f8cff,#7c5cff);
  --shadow-1:0 2px 8px rgba(0,0,0,.18);
  --shadow-2:0 8px 32px rgba(0,0,0,.28);
  --shadow-3:0 12px 40px rgba(0,0,0,.35);
  --highlight:inset 0 1px 0 rgba(255,255,255,.25);
  --blur:18px; --saturate:160%;
}

:root[data-theme="minimal"]{
  --bg-base:var(--n-50); --surface:var(--n-0); --surface-strong:var(--n-0);
  --border:rgba(16,24,40,.08);
  --text-1:var(--n-900); --text-2:var(--n-600); --text-3:var(--n-400);
  --shadow-1:0 2px 8px rgba(16,24,40,.06); --shadow-2:0 8px 24px rgba(16,24,40,.08);
  --highlight:none; --blur:0px;
}
:root[data-theme="aurora"]{
  --bg-base:#070b16; --surface:rgba(255,255,255,.05); --border:rgba(255,255,255,.12);
  --shadow-1:0 2px 8px rgba(0,0,0,.4); --shadow-2:0 10px 36px rgba(0,0,0,.5);
}
```

**页面里禁止裸值**：不能出现 `#333`、`border-radius:10px`、`box-shadow:0 0 4px #000`，一律引用 token。Stylelint 配自定义规则强制。

---

## 四、排版与色彩规则

- 字体栈：`-apple-system, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif`
- 字号阶梯：32/24/20/16/14/13/12，只用这 7 档
- 字重只用 400/500/600；标题 600，强调 500，正文 400
- 行高：标题 1.25，正文 1.55
- 数字：金额/倒计时/统计一律 `font-variant-numeric: tabular-nums`，金额右对齐
- 颜色：主色只出现在主按钮/选中态/关键数据；语义色只表达状态，不做装饰
- 深色底文字三级：95% / 66% / 42%；浅色底用 n-900 / n-600 / n-400

---

## 五、空间、圆角、阴影（三级层次）

- 间距只用 8/16/24/32/40；卡片内边距 16（紧凑）/20（常规）；区块间距 24/32
- 圆角四档：8（标签/输入）/12（小卡）/20（卡片/面板）/28（底部抽屉/大弹窗）
- 阴影三级：
  - L1 卡片：`--shadow-1`
  - L2 浮起（悬浮按钮/下拉）：`--shadow-2`
  - L3 模态（弹窗/抽屉）：`--shadow-3`
- **禁止**同屏出现 3 种以上阴影强度；禁止纯黑小阴影

---

## 六、组件规范（14 个，统一用 token）

| 组件 | 规格要点 |
|---|---|
| GButton | 三型（主/次/文字）× 四态；主按钮品牌渐变；按下 scale .97；最小高度 44px |
| GCard | surface + 1px border + r-20 + shadow-1（Glass 主题额外加 highlight） |
| GInput | 高 44/48，聚焦 2px 品牌描边（不用发光） |
| GNavBar | 高 44 + 安全区；Glass 半透明，Minimal 纯白 + 底部 1px 8% 分割 |
| GTabBar | 高 56 + 安全区；选中态图标与文字同步变色 |
| GSheet | 底部抽屉 r-28 + 拖动把手（40×4，圆角 2） |
| GDialog | 居中 r-20，宽 80%，移动端按钮上下排列 |
| GToast | 顶部浮层 r-12，2.5s 消失，不遮挡导航 |
| GSkeleton | 骨架块 r-8，微光扫过 1.6s 循环（唯一允许的循环动画） |
| GEmpty | 线性插画 + 一句解释 + 一个可操作按钮（禁止只有"暂无数据"） |
| GError | 图标 + 原因 + 重试按钮 |
| GTripCard | 起终点、时间、价格、余量热度条、车主信任分；信息层级 ≤3 行 |
| GCouponCard | 左金额区 + 右信息区 + 虚线撕口；三态：可领/已领/已用（灰化+印章） |
| GChatBubble | 用户=品牌渐变实色，助手=surface 玻璃；最大宽 78% |

组件统一在 **Storybook** 维护（每个组件含全状态 story），既是文档也是视觉回归基线。

---

## 七、动效规范

| 场景 | 时长 | 曲线 | 说明 |
|---|---|---|---|
| 按钮/卡片按下 | 160ms | ease-out-expo | scale .97 / 背景加亮 |
| 页面转场 | 240ms | ease-out-expo | 右进左出，同层不叠加动画 |
| 底部抽屉 | 320ms | ease-out-expo | 位移 + 遮罩淡入 |
| 列表插入/删除 | 240ms | ease-out-expo | TransitionGroup 位移+透明度 |
| 数字变化 | 420ms | ease-out | 金额滚动或淡入切换 |
| 骨架微光 | 1.6s | linear | 唯一允许循环 |
| 撕券 | 320ms | ease-out-expo | clip-path 路径动画 |

禁用：无限旋转 loading、弹跳动画、超过 500ms 的过渡、多条动画同时抢注意力。

---

## 八、状态设计（最容易忽略、也最显廉价）

每个页面/列表/表单必须交付：
- 加载中：骨架屏（列表）/ 按钮内 loading（提交）
- 空状态：插画 + 原因 + 下一步动作
- 错误：网络/服务/权限三类文案 + 重试
- 成功：轻提示（Toast）或页面内状态变化，不用弹窗打断
- 禁用：降低透明度 + 禁止点击 + 说明原因
- 边界：长文本截断、超长列表虚拟滚动、金额为 0、券过期、座位售罄

---

## 九、适配与可访问性

- H5 移动优先：基准 375，适配 320–430；安全区 `env(safe-area-inset-bottom)`
- 点击区 ≥ 44×44px；文字对比度 ≥ 4.5:1
- 尊重 `prefers-reduced-motion`：关闭非必要动效
- 图片懒加载 + 占位；仅用系统字体（不加载 web font）
- 横屏/平板：卡片网格自适应，不拉伸变形

---

## 十、性能预算（硬指标）

| 指标 | 预算 |
|---|---|
| 首屏 JS（gzip） | ≤ 180KB |
| 首屏 LCP（Fast 3G 模拟） | ≤ 2.5s |
| 动画帧率 | ≥ 55fps（中端安卓） |
| 单页玻璃层 | ≤ 3 层 |
| 列表 DOM 节点 | ≤ 200（超出用虚拟滚动） |

---

## 十一、前端工程生态（"生态要做好"的落点）

**技术栈**：Vue3 + TypeScript strict + Vite5 + Pinia + Vue Router + Vant4（主题变量定制）+ Element Plus + ECharts + VueUse

**组件与文档**
- 自研 `g-ui` 组件包（14 个组件 + token 入口），H5 与后台共用基础件
- **Storybook**：组件文档 + 全状态 story + 视觉基线截图

**质量工具链**
- ESLint + Prettier + Stylelint（自定义规则禁止裸色值/裸圆角/裸阴影）
- Vitest：组件与工具函数单测
- Playwright：E2E + 关键页截图对比（视觉回归）
- Husky + commitlint：提交前 lint + **中文提交规范**
- CI：lint → 单测 → 构建 → E2E → 产物

**请求与状态**
- axios 封装：token 注入 / 401 刷新 / 统一错误 / traceId
- Pinia：user / theme / trip / coupon 四个 store
- 主题切换：`data-theme` + token，首屏内联脚本避免闪烁

**目录约定**
```
src/
├─ design/        tokens.css、theme.css、reset.css
├─ g-ui/          自研组件（与 Storybook 同步）
├─ api/           按服务拆
├─ stores/
├─ views/
├─ hooks/         业务 hooks（useTheme/useCountdown/useVirtualList）
└─ utils/
```

---

## 十二、落地顺序（阶段 6 执行）

1. tokens + reset + 三主题（半天）
2. g-ui 基础件 6 个（Button/Card/Input/NavBar/TabBar/Toast）（1–2 天）
3. 业务组件 6 个（TripCard/CouponCard/SeatBar/StatusTimeline/Countdown/Skeleton）（2 天）
4. 页面套用（首页/搜索/详情/下单/支付/券包/我的/助手）（2 天）
5. 动效 + 状态补齐 + 视觉回归（1 天）
6. Storybook 文档 + 验收清单过一遍（半天）

---

## 十三、精致度验收清单（20 条，交付前逐条打勾）

1. 页面里没有裸色值/裸圆角/裸阴影
2. 全屏只有 1 主色 + 1 强调色
3. 字号只出现 32/24/20/16/14/13/12
4. 间距全部是 8 的倍数
5. 圆角只用 8/12/20/28
6. 阴影只用三级
7. 所有按钮都有四态
8. 所有列表都有 骨架/空/错误 三态
9. 所有表单都有校验提示与禁用态
10. 金额/倒计时为等宽数字且右对齐
11. 图标同一套、同一描边、同一视觉重心
12. 点击区 ≥44px
13. 长文本有截断策略
14. 图片有占位与懒加载
15. 页面转场与抽屉动效曲线一致
16. 无无限循环动画（骨架除外）
17. 深色主题文字对比度达标
18. 低端机降级可用（玻璃→半透明纯色）
19. 首屏 JS ≤180KB，LCP ≤2.5s
20. Storybook 里 14 个组件全状态可预览

---

## 十四、简历与面试怎么讲

> "自建一套前端设计体系：双级 design token、三套主题（玻璃/极简/深色）、14 个统一组件、Storybook 文档与视觉回归；用 Stylelint 规则禁止裸样式、Playwright 做截图对比，保证跨页面一致性；移动端玻璃层数控制在 3 层以内并做性能降级，首屏 JS 控制在 180KB。"

讲的是**设计系统思维 + 前端工程化**，不是"我会写 CSS"。
