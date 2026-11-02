# GoShare 简历素材 · 阶段 7 测试收口（可直接粘贴）

> 用途：后端开发 / AI 测试开发双线简历项目段落。数据全部来自真实验证（Jacoco 报告 / llm-stats / Playwright 运行），可面试追问。

## 一、项目简介（一句话）

GoShare 顺风拼车平台：Spring Boot 微服务（11 模块）+ Vue3 三端，覆盖发单/撮合/下单/支付/营销/候补全链路，内置 LlmGateway 省 token 四关卡与 AI 测试体系。

## 二、后端段（推荐 4 行内）

```
个人项目 GoShare 顺风拼车平台（Spring Boot 微服务 + Nacos/ES/RocketMQ/ShardingSphere + Vue3）

· 微服务拆分 11 模块（用户/行程/订单/支付/营销/通知/AI 助手），Gateway 统一鉴权 + Sentinel 限流，
  RocketMQ 延时消息实现订单超时自动释放，ShardingSphere 订单分库分表
· LlmGateway 省 token 四关卡：答案缓存 / 规则与小模型先答 / 固定前缀防注入 / 夜间预生成，
  实测 28 次真实请求中 78.6% 零大模型调用（缓存命中 64.3% + 规则拦截 14.3%），统计来自 llm_call_log
· 智能撮合：NL 解析 + ES 语义检索 + MatchReranker 特征细排（修复兜底排序），金额/库存/排序全部纯 Java 计算不依赖 LLM
```

## 三、AI 测试段（推荐 3 行内）

```
· 测试收口：Jacoco 行覆盖门禁从根聚合升级为模块级独立拦截，6 个业务模块全部 ≥70%（合计 72.2%、977 行），
  阈值 0.71 反向验证门禁真实生效；新增 16 个测试类约 127 用例，覆盖 LLM 客户端降级/缓存、支付幂等、超时释放、ES 检索
· Playwright E2E 主链路（登录→语义搜索→下单→支付→订单 PAID）2/2 稳定通过，用例自带数据自愈（过期行程自动重新发布）
· CI 四步门禁一键全绿并落地 GitHub Actions 三 job（后端覆盖率报告上传 / 前端类型+单测+Stylelint 裸样式门禁 / E2E）；
  前端 20 条精致度验收配套 Stylelint 强制设计 token（禁裸色值/字号/间距）
```

## 四、可深挖数据（面试备用）

| 指标 | 值 | 来源 |
|---|---|---|
| 6 业务模块行覆盖 | assistant 70.0% / marketing 72.7% / order 70.4% / payment 82.4% / trip 80.4% / user 75.8% | target/site/jacoco/jacoco.csv |
| ALL-6 合计 | ≈72.2%，covered≈977 行 | 同上 |
| 单测 | 约 127 用例全绿 | mvn verify |
| E2E | 2/2 通过（主链路 6.2s + AI 助手 596ms） | playwright test |
| llm-stats（28 请求累计） | cacheHitRate 64.3%、gateSkippedRate 14.3%、零模型调用 78.6%、节省 1003 tokens | GET /api/assistant/llm-stats |
| CI 门禁 | mvn verify + vue-tsc + vitest 20/20 + stylelint 0 错误 | scripts/ci.ps1 |

## 五、口径说明（防追问）

- 覆盖率口径：6 业务模块各自 ≥70%，排除 `**/*Application.class` + config/dto/vo 包；gateway/common/api 无业务代码不参与。
- 省 token 口径：`cacheHitRate=cacheHit/total`、`gateSkippedRate=gateSkipped/total`、`modelCalled=total-cache-gate`；实测为 http 接入模式（本地 9999 假端点验证真实网络路径 + 失败降级容错）。
- 截图视觉回归未做，样式层由 Stylelint 门禁 + 构建 + 20 条精致度验收兜底，如被追问按此说明。
