# GoShare 简历素材（可直接粘贴 · 2026-11-06 更新）

> 用途：后端开发 / AI 测试开发双线简历项目段落。数据全部来自真实验证（Jacoco 报告 / llm-stats / Playwright 与 GitHub Actions 运行），可面试追问。

## 一、项目简介（一句话）

GoShare 顺风拼车平台：Spring Boot 微服务（10 模块 / 8 服务）+ Vue3 三端，覆盖发单/撮合/下单/支付/营销/候补全链路，内置 LlmGateway 省 token 四关卡与 AI 测试体系。

## 二、后端段（推荐 4 行内）

```
个人项目 GoShare 顺风拼车平台（Spring Boot 微服务 + Nacos/ES/RocketMQ/ShardingSphere + Vue3）

· 微服务拆分 8 个服务（用户/行程/订单/支付/营销/通知/AI 助手 + 网关），Gateway 统一鉴权 + Sentinel 限流，
  RocketMQ 延时消息实现订单超时自动释放，ShardingSphere 订单分库分表
· LlmGateway 省 token 四关卡：答案缓存 / 规则与小模型先答 / 固定前缀防注入 / 夜间预生成，
  实测 28 次真实请求中 78.6% 零大模型调用（缓存命中 64.3% + 规则拦截 14.3%），统计来自 llm_call_log
· 智能撮合：NL 解析 + ES 语义检索 + MatchReranker 特征细排（修复兜底排序），金额/库存/排序全部纯 Java 计算不依赖 LLM
```

## 三、AI 测试段（推荐 3 行内）

```
· 测试收口：Jacoco 行覆盖门禁从根聚合升级为模块级独立拦截，7 个模块全部 ≥70%（70.2%–92.2%），
  阈值 0.71 反向验证门禁真实生效；295 个单测（40 个测试类），覆盖退款闭环/改签、履约状态机、区间库存、LLM 降级与密钥加载
· Playwright E2E 16/16 稳定通过（6 个 spec：主链路下单支付 / 订单详情改签 / 券包 / 路由可达 / AI 对话 / 验收修复），
  用例自带数据自愈（过期行程自动重新发布），在 GitHub Actions 全新库上跑通
· CI 四步门禁一键全绿并落地 GitHub Actions 三 job（后端覆盖率门禁+报告上传 / 前端类型+单测+Stylelint 裸样式门禁 / E2E 起全栈 8 服务跑真实浏览器）；
  前端 20 条精致度验收配套 Stylelint 强制设计 token（禁裸色值/字号/间距）
```

## 四、可深挖数据（面试备用）

| 指标 | 值 | 来源 |
|---|---|---|
| 7 模块行覆盖 | user 92.2% / trip 85.6% / payment 82.6% / order 72.8% / marketing 72.7% / assistant 71.0% / notify 70.2% | target/site/jacoco/jacoco.csv |
| 单测 | 295 用例全绿（40 个测试类） | mvn verify |
| E2E | 16/16 通过（6 个 spec，含履约与改签） | playwright test / GitHub Actions |
| llm-stats（28 请求累计） | cacheHitRate 64.3%、gateSkippedRate 14.3%、零模型调用 78.6%、节省 1003 tokens | GET /api/assistant/llm-stats |
| CI 门禁 | mvn verify + vue-tsc + vitest 20/20 + stylelint 0 错误 + E2E 16/16 | scripts/ci.ps1 / .github/workflows/ci.yml |

## 五、口径说明（防追问）

- 覆盖率口径：7 个模块（含 notify）各自 ≥70%，排除 `**/*Application.class` + `**/config/**`；gateway/common/api 无业务代码不参与。
- 省 token 口径：`cacheHitRate=cacheHit/total`、`gateSkippedRate=gateSkipped/total`、`modelCalled=total-cache-gate`；实测为 http 接入模式（本地 9999 假端点验证真实网络路径 + 失败降级容错）。
- 截图视觉回归未做，样式层由 Stylelint 门禁 + 构建 + 20 条精致度验收兜底，如被追问按此说明。
