# GoShare 顺风拼车平台 —— 亮点技术与重要技术整理

> 个人求职项目（不上线）：以 12306（座位库存/并发下单/订单支付）和牛券（券引擎/结算/压测）为蓝本爆改的顺风拼车平台，含 AI 出行助手。
> 后端：Java 17 + Spring Boot 3.2 + Spring Cloud Alibaba 微服务（11 模块 / 8 服务）；前端：Vue3 H5 三端；测试：单测/Jacoco≥70%/E2E/压测/CI 全链路。

---

## 一、微服务架构与治理（重要技术）

| 技术 | 落地内容 | 亮点/证据 |
|---|---|---|
| Spring Cloud Alibaba + Nacos | 8 个服务全部注册发现，服务间调用服务名 + `lb://` 负载均衡 | 带券下单、支付回调跨服务链路验证通过；修复 Gateway WebFlux 与 Servlet 冲突 |
| Spring Cloud Gateway 统一入口（8000） | 7 条路由指向服务名；全局 JWT 鉴权（白名单外强制 Bearer，透传 X-User-Id）；Redis 令牌桶限流 | 伪造 token 401、单 IP 20qps/突发 40 实测拦截 |
| Sentinel 流量防护 | 网关侧接入 Sentinel（SCA 集成） | 压测场景B：100 线程并发 60 个 429 被拒，40 放行 |
| 多模块工程 | 父 POM + common + api + 8 服务；api 模块统一 DTO/VO/Feign 客户端 | fat jar 启动方案解决 CreateProcess 206 命令行超长 |

## 二、并发与不超卖防线（核心亮点）

| 技术 | 落地内容 | 亮点/证据 |
|---|---|---|
| Redis Lua 原子扣减 + DB 条件更新双写 | 行程座位库存：Redis 预初始化，下单 Lua 原子扣，DB `seat - 1 where seat > 0` 兜底 | 压测场景A：50 线程抢 3 座，DB 实建 3 单、余座 0、零超卖 |
| 幂等键表 + 唯一索引 | 下单幂等（`idempotent_key` 唯一索引兜底）、重复下单返回首次结果 | 联调：重复下单被拦，返回原订单 |
| 支付回调幂等 + 重复支付拦截 | 回调幂等键 + 订单状态机校验 | 状态机防重复是全局设计主线 |
| 候补递补 | 满座登记（Redis ZSet 按时间排序），订单取消/超时释放后候补第一位自动补位（固定幂等键防重复递补） | 满座→取消→自动补位→队列清空 全链路验证 |
| RocketMQ 延时消息超时释放 | 下单发 10 分钟延时消息（Tag=ORDER_TIMEOUT，Keys=订单号），消费者关单；Redis 消费去重 + 状态机双防线；12 分钟定时扫描兜底 | 修复 Spring Message header 转换导致 Broker 侧 Tags=null 的根因（mqadmin 实证）；主链路 + 兜底双路径验证 |
| 秒杀券 | Redis Lua 原子扣减做闸门 + DB 条件更新 received_count 兜底防超发 | 限量 2 第 3 人抢光、重复领/DB 满时 Redis 回补 全验证 |

## 三、数据层（重要技术）

| 技术 | 落地内容 | 亮点/证据 |
|---|---|---|
| ShardingSphere-JDBC 5.5.0 分库分表 | 订单表按乘客 ID 取模分片到 trip_order_0/1；雪花算法主键；更新强制携带分片键；老表改名兜底 + 数据迁移脚本 | 乘客 ID 2→表0 / 3→表1 路由验证、跨片查询 |
| Elasticsearch 行程搜索 | 发布同步索引（goshare_trip），搜索 ES 优先 + DB 实时余座校准，ES 不可用自动降级 MySQL；全量同步运维接口 | ES 不可用降级是演示级高可用的关键设计 |
| Redis 多场景 | 答案缓存（版本号作废）、幂等键、令牌桶限流、候补 ZSet、撮合多级缓存、消费去重 | Redis 故障时缓存降级兜底（曾修复 Redis 500） |
| MySQL + MyBatis-Plus | 核心业务表 + 结算引擎纯 Java（BigDecimal HALF_UP） | 金额一律代码产出，不靠浮点/LLM |

## 四、AI 出行助手（本轮最大亮点，8 个服务之一 8007）

### 4.1 LLM 网关四关卡 + 全量记账（省 token 实测）
- 关卡1 答案缓存：`ai:ans:v{dataVersion}:{md5}` TTL 12h，**数据版本号 +1 旧答案自动作废**（曾作废错误农历/春节答案）
- 关卡2 小模型先答：规则意图（取消收费/违约金/候补/用券/历法等）零 LLM 调用
- 关卡3 固定 SYSTEM_PREFIX（厂商缓存复用 + 防注入）
- 关卡4 夜间预生成热点问答（凌晨 3 点 @Scheduled）
- `llm_call_log` 全量记账 + `GET /api/assistant/llm-stats` 统计（实测 totalRequests≈195、cacheHit 42.6%、gateSkipped 29.2%）

### 4.2 结构性防幻觉（两轮重构的核心，简历重点）
- **确定性知识全工具化**：Function Calling 8 个工具（search_trips / get_weather / get_lunar_date / get_current_time / get_festival_countdown / get_year_info / get_jieqi_date / get_date_calc），模型只能经工具取数；工具无能力时模型如实拒绝，禁止裸生成
- **离线确定性历法**：lunar-java 1.7.7，农历/干支/生肖/春节倒计时/节气 全本地计算 0 token 0 key
- **输出护栏（第二道防线）**：模型最终轮未调工具却吐出日期/农历/倒计时天数/价格/余座/星期等硬断言 → 判定疑似幻觉 → 强制带护栏重试 → 仍编造就如实降级「抱歉，这项数据我暂时无法确认」
- 真实实测：模型编造「2027 春节 2月1日」/「距元旦 69 天」/「12月15日周一」全部被拦截或修正为「2027-02-06」「95 天」「周二」
- 超时重试：RestTemplate connect 3s / read 15s + 调用失败自动重试

### 4.3 A1 语义搜索与智能撮合（列表/排序/金额一律代码产出）
- NL→结构化：规则模板解析（从X到Y/今天/明天/周X/时段/大件/便宜，未识别条件不参与）
- 两阶段召回排序：ES 动态 DSL 粗排 → CompletableFuture 并行取信任分+备注 → 细排打分（时间 0.35/余座 0.25/信任 0.25/价格 0.15，大件降权）
- 特征解释层：每个候选输出总分 + 命中原因（"为什么推荐这几单"可追溯）

### 4.4 A2 取消挽留 + B1 工单摘要
- 取消判责 FeeCalculator 纯 Java（距发车 ≥120min 无责全额退；<120min 实付 20% 违约金封顶 50）
- LLM 只生成话术，金额/行程数据代码注入；Redis 幂等（同一订单只挽留一次 24h）
- 工单会话摘要 + 分类模板回复草稿（含订单号可直接发）

### 4.5 真实数据接入
- 天气接 Open-Meteo 真实数据（替换 mock），短缓存 15 分钟
- 天气城市提取修复：按问句出现位置取首个城市（"邯郸今天天气和北京到邯郸…"取邯郸）
- AI 注入今日日期 + 多轮上下文（前后端）

## 五、前端（Vue3 H5 三端）

| 技术 | 落地内容 |
|---|---|
| 工程 | Vue3 + TS strict + Vite5 三入口（h5/admin/screen）+ Pinia + Router + axios，H5 主包 gzip 33KB（预算 180KB） |
| 双级 design token | L1 基础（色/灰阶/字号七档/间距 8pt/圆角/阴影/动效）→ L2 语义三主题（Glass 玻璃拟态 / Minimal / Aurora 大屏），`data-theme` 一键切换，页面禁裸样式值（Stylelint 门禁） |
| 14 个 g-ui 组件 | GButton 三型四态 / GSheet 抽屉 / GToast / GChatBubble / GTripCard 热度三色条 / GCouponCard 虚线撕口三态 + 已用印章 等 |
| 数据大屏 | ECharts 价格分布/信任分布/LLM 缓存命中率（真实 llm-stats） |

## 六、测试与工程质量（AI 测试开发线亮点）

| 项 | 内容与证据 |
|---|---|
| 后端测试 | **100+ 单测全绿**（金额边界/状态机/历法三源锚点/护栏/工具），**Jacoco 行覆盖 ≥70% 门禁**（CI 强制） |
| 前端测试 | Vitest 20 用例（金额/时间/倒计时 utils + 组件四态/三态）；Storybook 14 组件全状态预览 |
| E2E | Playwright 主链路 14/14 全绿（登录→搜索→AI→下单→支付→券） |
| 压测 | JMeter 双场景：50 并发抢 3 座零超卖 / 100 并发网关限流 60×429；完整报告 |
| CI | `ci.ps1` 串联：编译→单测→覆盖率门禁→E2E→报告 |
| 验收文档 | 每阶段验收报告打勾（阶段6 前端 20 条精致度、阶段7 测试收口） |

## 七、可面试深挖的问题（自测）
1. 防超卖三层防线分别解决什么问题？（Lua 原子性 / DB 条件更新兜底 / 幂等键）
2. 为什么订单要分库分表、分片键怎么选、更新为什么必须带分片键？
3. RocketMQ 延时消息的 Tag 丢失根因是什么？消费防重复两道防线？
4. LLM 幻觉为什么提示词治不了根？FC 工具化 + 输出护栏分别拦截哪类错误？
5. 省 token 四关卡各自省在哪？gateSkipped/cacheHit 怎么量化？
6. ES 与 MySQL 双写如何保证一致？ES 挂了怎么办？
7. 结算为什么必须 BigDecimal？违约金封顶 50 的边界测试怎么设计？
