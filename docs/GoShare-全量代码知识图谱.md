# GoShare 全量代码知识图谱

> 用途：把整个仓库"读成一张图"——规模盘点、端到端调用链、文件级职责地图、关键不变量、配置矩阵、测试矩阵、一致性审计（含缺陷清单）、证据索引。
> 口径：以仓库当前代码为准（152 个 main Java 类 / 8376 行，29 个测试类 / 3416 行，前端 src 45 个文件 / 4412 行）。
> 与既有文档的分工：`方案-v2.0.md` 讲"要做什么"，`技术亮点整理.md` 讲"亮点是什么"，本文只讲"代码实际长什么样、哪里对得上、哪里对不上"。

---

## 一、规模盘点

| 维度 | 数量 | 说明 |
|---|---|---|
| Maven 模块 | 11 | `common` + `api`（纯聚合 POM，无源码）+ `gateway` + 8 个业务服务 |
| Java 主源码 | 152 个 / 8376 行 | 不含测试 |
| Java 测试 | 29 个 / 3416 行 | 约 127 个用例 |
| SQL 脚本 | 4 个 | 核心 6 表 / 券 3 表 / 分片 2 表 / AI 3 表 |
| 前端源码 | 45 个 / 4412 行 | 3 入口 + 8 个 H5 页面 + 14 个 g-ui 组件 |
| E2E 用例 | 5 个 spec | Playwright，375×812 移动视口 |
| 压测计划 | 2 个 JMX | 场景 A 防超卖 / 场景 B 网关限流 |

各模块类数量：assistant 47、marketing 22、user 23、order 22、trip 11、payment 11、notify 9、common 6、gateway 3。

---

## 二、端到端调用链

### 2.1 主链路：注册 → 认证 → 发布 → 搜索 → 下单 → 支付 → 超时关单

```
① 注册/登录（user:8001）
   POST /api/user/register → UserController → UserServiceImpl
     · 手机号唯一（selectCount 前置查重 + 表唯一索引兜底）
     · BCrypt 加密落 password_hash
     · 昵称缺省 "乘客" + 手机号后 4 位（乘客9000）
     · 直接签发 token（注册即登录）
   POST /api/user/login → 校验 BCrypt + status=1，签发 JWT（subject=userId，claim role）

② 网关鉴权（gateway:8000）
   AuthGlobalFilter（order = -100）
     · 白名单：/api/user/login、/api/user/register、/api/ping、/actuator
     · 解析 JWT → 注入 X-User-Id 向下游透传，命中则写 JSON 401
     · 订单路由额外挂 RequestRateLimiter（令牌桶 20 qps / 突发 40，key = clientIp）

③ 发布行程（trip:8002）
   POST /api/trip/publish → TripServiceImpl.publish
     · 校验发车时间必须在未来
     · insert trip（remain_seats = total_seats）
     · Redis set trip:seats:{id} = totalSeats   ← 座位库存的"热"副本
     · tripEsService.index(trip)                ← 双写 ES 索引（失败仅告警）

④ 搜索行程（trip:8002）
   GET /api/trip/search → ES 优先（CriteriaQuery：起终点 + status + 日期范围）
     → 拿到 id 列表 → DB selectBatchIds 回填真实余座（校准，防 ES 脏数据）
     → 抛异常则 catch → searchFromDb（MySQL LIKE 降级，功能不断）
   详情 GET /api/trip/{id} → ensureStock 自愈：Redis 无 key 则从 DB 回填

⑤ 下单（order:8003）—— 全项目最关键的一段
   POST /api/order/create → OrderServiceImpl.create
     (a) 幂等前置：idempotency_key 表 selectOne → 命中直接回放原订单
     (b) 用券：调 marketing /settle 锁券取 discount（失败不阻断下单，best-effort）
     (c) Redis Lua 原子扣减（DEDUCT_LUA）：
           -1 → 已售罄；-2 → 库存未初始化（按 DB 回填后重试）
     (d) DB 条件扣减兜底：
           UPDATE trip SET remain_seats = remain_seats - n
           WHERE id = ? AND remain_seats >= n AND status IN (0,1)
         影响行数 0 → RESTORE_LUA 回滚 Redis + 退回券 + 抛"座位不足"
     (e) insert trip_order（按 passenger_id % 2 路由 trip_order_0/1）
     (f) insert idempotency_key（唯一索引 = 最后一道并发防线）
     (g) 发 RocketMQ 延时消息：delayLevel = 14（10 分钟），Tag = ORDER_TIMEOUT

⑥ 支付（payment:8005）
   POST /api/payment/pay → PaymentServiceImpl.pay
     · 远程取订单，状态必须 CREATED（否则 400"订单不可支付"）
     · 复用已有 PENDING 记录或新建 payment（paymentNo = P + yyyyMMddHHmmss + 6 随机位）
     · 模拟扣款成功 → 调 order /pay-callback
     · 回调失败 → payment 置 FAILED + 抛 500；成功 → SUCCESS + paidAt

⑦ 支付回调（order:8003）
   POST /api/order/pay-callback → handlePayCallback
     · 状态机 CREATED → PAID（非 CREATED 且已 PAID 且 paymentNo 相同 → 幂等返回）
     · 调 marketing /confirm 把券 锁定(3) → 已用(1)

⑧ 超时关单（两条腿，缺一不可）
   腿 1 —— MQ：OrderTimeoutConsumer
     @RocketMQMessageListener(topic = goshare-order-topic, selectorExpression = "ORDER_TIMEOUT")
     去重：Redis setIfAbsent("order:timeout:consume:{orderNo}", 1, 1h) 为 false 直接 return
     → orderService.closeExpiredOrderByNo(orderNo)
   腿 2 —— 兜底扫描：@Scheduled(fixedDelay = 60_000) releaseExpiredOrders
     按 scheduledTimeoutMinutes = 12（比 MQ 的 10 分钟更宽）扫 CREATED 超时单
   关闭动作：状态置 CLOSED + RESTORE_LUA 还座 + 退券 —— 非 CREATED 单一律静默跳过
```

**为什么延时消息用原生 `org.apache.rocketmq.common.message.Message`**：`rocketmq-spring` 的 `RocketMQTemplate.syncSend` 会把 `delayLevel` 写进消息头，但转换后 `Tags` 为 null，导致消费者 `selectorExpression` 过滤不到。代码里改为直接构造原生 Message 并显式 `setDelayTimeLevel(14)` + `setTags("ORDER_TIMEOUT")`。

### 2.2 券链路（marketing:8004）

```
模板创建 POST /api/coupon-template
  CouponTemplateServiceImpl.create
    · 校验 endTime > startTime；expireDays 缺省 7
    · totalCount > 0 才初始化 Redis coupon:stock:{id}（不限量模板不进 Redis）

领券（含秒杀） POST /api/coupon/receive
  CouponServiceImpl.receive
    · SECKILL_LUA 原子预扣（Redis 层挡掉绝大部分流量）
    · DB 条件更新 received_count < total_count（Redis 与 DB 双写一致性兜底）
    · insert user_coupon ← uk_template_user 唯一索引
        命中 DuplicateKeyException → Redis increment 回补 → 抛"每人限领一张"
        其他异常 → 同样 Redis 回补
    · 落 coupon_log（RECEIVE）

用券结算 POST /api/coupon/settle（由 order 服务内部调用）
  lockCoupon：UPDATE ... SET status=3, order_no=? WHERE id=? AND user_id=? AND status=0 AND expire_at > NOW()
    影响 0 行 → "券不可用（未使用或已过期）"
  CouponCalculator.calcDiscount（纯 Java，BigDecimal HALF_UP）
    FULL_REDUCTION：满 threshold 减 discountValue
    DISCOUNT：总价 × (1 - rate)，封顶 discountValue
    CASH：直减 discountValue
  返回 SettleResult{discount, payPrice}

确认用券 POST /api/coupon/confirm   → status 3 → 1（重复回调幂等，不报错）
退券     POST /api/coupon/return    → status 3 → 0，order_no 置 NULL
```

券状态机：`0 未使用 → 3 锁定中 → 1 已使用`，`3 → 0` 退回，`2 已过期`。

### 2.3 AI 助手链路（assistant:8007）

```
POST /api/assistant/chat → LlmGateway.ask(question, history, tier)
  关卡 0  答案缓存   ai:ans:v{dataVersion}:{md5(question)}  TTL 12h（天气类 15min）
  关卡 1  天气规则   WeatherRuleProvider：城市表首现优先 + 相对日期解析 + 历史追问补全
  关卡 2  行程规则   TripRuleProvider：clarify（缺起终点反问）/ fetch（查真实班次）/ ask（只给日期）
  关卡 3  时间规则   TimeRuleProvider：几点/周几/今天几号
  关卡 4  农历规则   LunarRuleProvider：农历/阴历/老黄历/春节/节气/今年什么年（离线 lunar-java，0 token）
  关卡 5  业务规则   RuleAnswerProvider：9 条 FAQ（取消扣费/候补/用券/退款…）
  关卡 6  FC 工具通道 最多 MAX_TOOL_ROUNDS = 3 轮，8 个工具，tool 消息回填后二次调用
  关卡 7  输出护栏
           HARD_ASSERTION 命中（"2027年春节是2月1日"这类日期/金额硬断言）
           且本轮未调用任何工具 → 强制重试一轮；仍失败 → 返回 UNVERIFIED_FALLBACK
           确定性域问题但模型如实拒答（无硬断言）→ 不误伤，直接放行
  全量记账 → llm_call_log(bizType, cacheHit, gateSkipped, inputTokens, outputTokens)

8 个工具：search_trips / get_weather / get_lunar_date / get_current_time
          get_festival_countdown / get_year_info / get_jieqi_date / get_date_calc

夜间预热 @Scheduled(cron = "0 0 3 * * ?") prewarmHotQuestions()：5 条 HOT_QUESTIONS 提前灌缓存
统计 GET /api/assistant/llm-stats → LlmStatsService：缓存命中率 / 规则拦截率 / 模型直调数 / 省下的 token 与费用
```

A1 语义搜索：`/api/assistant/semantic-search`
`NlTripParser`（规则模板抽 起终点/日期/时段/人数）→ `TripEsSearcher` 动态 Criteria（时段偏好 ±2h）→ `CompletableFuture` 并行拉信任分与备注 → `MatchReranker` 打分（时间 0.35 / 余座 0.25 / 信任 0.25 / 价格 0.15，备注缺"可带大件"扣 0.15）→ 解释层输出人话理由。

A2 取消挽留：`/api/assistant/cancel-preview` → 费用明细回显 + 同路线替代方案（排除当前行程、只留未来班次）+ LLM 个性化挽留话术；Redis `ai:cancel-retention:{orderNo}` 保证一单只挽留一次（24h）。

B1 工单：`/api/assistant/work-order/analyze` → 关键词分类（退款/改期/候补/其他）+ 规则摘要 + 模板草稿；Redis 幂等保证摘要只生成一次。

### 2.4 天气链路（notify:8006）

`WeatherService` 接口双实现，`@ConditionalOnProperty(goshare.weather.mode)` 切换：
- `MockWeatherService`（`matchIfMissing = true`，5 城市）
- `HttpWeatherService`：Open-Meteo geocoding + forecast，WMO code 映射中文，按温度/降水给出行建议，失败返回"暂不可用"而不编造。

`BeanConfig` 里两个 RestTemplate：`@Primary @LoadBalanced`（走 `lb://` 服务发现）+ `plainRestTemplate`（直连外网，不能带负载均衡拦截器）。

---

## 三、文件级职责地图

### 3.1 `go-share-common`（6）
| 类 | 职责 |
|---|---|
| `Result<T>` | 统一响应 `{code, message, data}`，`success()` / `error()` |
| `ResultCode` | 200 / 400 / 401 / 403 / 404 / 500 常量 |
| `BusinessException` | 携带业务 code + message |
| `GlobalExceptionHandler` | IllegalArgument→400、Business→自带 code、Exception→500"系统繁忙，请稍后再试" |
| `JwtUtil` | HS256 签发/解析，subject=userId，claim role，可配过期小时 |
| `CurrentUserResolver` | 从网关注入的 `X-User-Id` 取用户，缺失/非法抛 401 |

### 3.2 `go-share-gateway-server`（3）
`AuthGlobalFilter`（鉴权 + 透传）、`RateLimitConfig`（`clientIpKeyResolver`）、`GatewayServerApplication`。
`application.yml`：7 条路由指向 `lb://`，仅订单路由挂 `RequestRateLimiter`（replenishRate 20 / burstCapacity 40），全局 CORS。

### 3.3 `go-share-user-server`（23）
控制器 `UserController`（register/login/me/`{userId}`/real-name-verify）、`DriverCertificationController`（apply/audit/mine）、`PingController`。
实现 `UserServiceImpl`（BCrypt、手机号唯一、默认昵称、注册即发 token）、`DriverCertificationServiceImpl`（`@Transactional` 审核通过把 user 翻成 DRIVER + driverVerified=1）。
实体 `User`（含 `trustScore` BigDecimal）、`DriverCertification`。配置 `MybatisPlusConfig`（分页）、`BeanConfig`（BCrypt Bean）。

### 3.4 `go-share-trip-server`（11）
`TripServiceImpl`：publish / search（ES 优先 + DB 校准 + 降级）/ detail（ensureStock 自愈）/ listByDriver / syncAllToEs。
`TripEsService`：index / remove / search，**全部 try-catch 吞异常只 warn**——ES 是加速层不是真相源。
`TripDocument`：`@Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)`。

### 3.5 `go-share-order-server`（22）—— 复杂度最高
| 类 | 关键点 |
|---|---|
| `OrderServiceImpl` | 见 2.1 ⑤⑧；`DEDUCT_LUA` 第 64 行、`RESTORE_LUA` 第 72 行、`@Scheduled(fixedDelay=60_000)` 第 346 行、Lua 执行在第 415/421 行 |
| `ShardingDataSourceConfig` | 手写 Hikari + `ShardingRuleConfiguration`，`algorithm-expression = trip_order_${passenger_id % 2}`（第 62 行），SNOWFLAKE 主键，`SingleRuleConfiguration` 收录 `idempotency_key` / `trip`，`sql-show=true` |
| `TripSeatMapper` | `@Update` 注解式条件扣减 / 还座 SQL |
| `TripOrderMapper` | 分片表 CRUD；所有更新都带 `passenger_id` 分片键 |
| `OrderTimeoutConsumer` | `selectorExpression = "ORDER_TIMEOUT"` + Redis 去重 |
| `FeeCalculator` | 纯 Java 退款计算：`PENALTY_RATE = 0.20`（第 20 行）、`FREE_CANCEL_MINUTES = 120`（第 26 行）、违约金封顶 50；内嵌 `FeeBreakdown` |
| `IdempotencyRecord` / Mapper | `idempotency_key` 表，唯一索引 |

### 3.6 `go-share-marketing-server`（22）
`CouponServiceImpl`（SECKILL_LUA 第 42 行、双写一致性、异常回补、`saveLog`）、`CouponTemplateServiceImpl`（创建校验 + 限量才初始化 Redis 库存）、`CouponCalculator`（三种券型纯函数）、`UserCouponMapper`（三条注解式条件更新构成状态机）、`CouponController` / `CouponTemplateController`。

### 3.7 `go-share-payment-server`（11）
`PaymentServiceImpl`：订单状态校验 → 复用/新建 PENDING → 模拟扣款 → 回调 → 成功 SUCCESS+paidAt / 失败 FAILED+抛 500 → `getByPaymentNo`。

### 3.8 `go-share-notify-server`（9）
`NotifyController`（`/weather`、`/trip-reminder` 拼装 订单→行程→天气 文案）、`WeatherService` 接口 + `HttpWeatherService` / `MockWeatherService`、`WeatherProperties`、`BeanConfig`（双 RestTemplate）。
注意：`NotifyServerApplication` 没有写 `scanBasePackages = "com.goshare"`（其他服务都写了），靠包路径同在 `com.goshare` 下仍能扫到 common 的 Bean，属风格不一致而非缺陷。

### 3.9 `go-share-assistant-server`（47）—— 文件最多
| 包 | 文件 |
|---|---|
| `llm` | `LlmGateway`（四关卡 + 护栏 + 预热 + 记账）、`LlmClient` 接口（`Tier`/`LlmResponse`/`ToolMessage`/`ToolCall`/`ToolChatResponse`）、`HttpLlmClient`、`MockLlmClient`、`LlmResult`、`LunarService`、`LunarRuleProvider`、`TimeRuleProvider`、`TripRuleProvider`、`WeatherRuleProvider`、`RuleAnswerProvider`、`DateParser` |
| `llm.tool` | `AssistantTool` 接口 + 8 个 Tool + `ToolRegistry` |
| `nl` | `NlTripParser`、`NlTripQuery` |
| `service` | `AiSearchService`、`MatchReranker`、`CancelRetentionService`、`WorkOrderService`、`LlmStatsService` |
| `es` | `TripEsSearcher`、`EsTripHit` |
| `controller` | `AiAssistantController`（semantic-search / chat / llm-stats / cancel-preview / work-order/analyze / work-order/mock / prewarm） |
| `entity`/`mapper`/`dto`/`config` | `LlmCallLog`、`WorkOrder`、`WorkOrderMessage`、`LlmProperties`、`BeanConfig`（3s/15s 超时） |

### 3.10 前端 `web/src`（45）
| 层级 | 内容 |
|---|---|
| 设计层 | `design/tokens.css`（L1 原子：色/灰阶/透明/字号 12-32/间距 8-40/圆角 2-8-12-20-28/时长）、`theme.css`（L2 语义：Glass / Minimal / Aurora，`data-theme` 切换）、`reset.css`（`.num` 等宽数字、44px 最小点击、`prefers-reduced-motion`、`backdrop-filter` 降级） |
| 组件层 | 14 个 g-ui：GButton / GCard / GInput / GNavBar / GTabBar / GSheet / GDialog / GToast / GSkeleton / GEmpty / GError / GTripCard / GCouponCard / GChatBubble |
| 页面层 | 8 个 H5：Home / Search / TripDetail / OrderPay / Pay / CouponList / Mine / Assistant；`admin/AdminApp.vue`；`screen/ScreenApp.vue` |
| 基建 | `api/request.ts`（token key `goshare-token`、Bearer 注入、code≠200 reject、401 清 token）、`api/{user,trip,order}.ts`、`utils/format.ts`（`fenToYuan`/`yuan`/`formatTripTime`/`countdownText`）、`router`、3 个 `main-*.ts` 入口 |

三入口 HTML 内联主题引导脚本（首屏前设好 `data-theme`，防白闪）。
`TripDetailView` 里的 `calcDiscount` 与后端 `CouponCalculator` 是**前端镜像实现**，改一处必须改两处。

---

## 四、关键不变量（Invariants）

这些是"面试问到这里必须能落地到代码"的硬约束：

| # | 不变量 | 落地位置 | 违反后果 |
|---|---|---|---|
| 1 | 任何路径不得超卖 | Redis Lua 原子扣减 + DB `remain_seats >= n` 条件更新 + 幂等表唯一索引（三层） | 座位变负、超卖 |
| 2 | 分片表更新必须携带分片键 | 所有 `LambdaUpdateWrapper` 同时 `eq(id).eq(passengerId)` | 广播全分片 / 更新错片 |
| 3 | 下单必须幂等 | `idempotency_key` 前置查询 + 唯一索引；候补单用固定键 `WAITLIST:{tripId}:{userId}` | 重复建单、重复扣座 |
| 4 | 关单只对 CREATED 生效 | `closeExpiredOrderByNo` / `closeExpiredOrder` 判状态，否则静默跳过 | 误关已支付订单 |
| 5 | 券状态只能单向流转 | `lockCoupon` 仅 `status=0 且未过期`；`useCoupon`/`returnCoupon` 仅 `status=3 且 order_no 匹配` | 一券多用 |
| 6 | ES 永远只是加速层 | 搜索后 DB 回填真实余座；ES 异常 catch 降级 MySQL | 展示假余座 → 下单失败 |
| 7 | AI 的确定性数据只能来自工具或离线算法 | 8 个 FC 工具 + `lunar-java` 离线历法 + 输出护栏三重 | 编造日期/价格 |
| 8 | 延时消息必须带 Tag | 原生 Message 显式 `setTags("ORDER_TIMEOUT")` | 消费者收不到 |
| 9 | 消息消费必须去重 | Redis `setIfAbsent(..., 1h)` | 重复关单/重复还座 |
| 10 | 跨服务调用失败不能阻断主流程（除支付回调） | 用券 settle/confirm/return 全部 try-catch best-effort；支付回调失败则显式回滚 | 下单被营销服务拖死 |
| 11 | 外部 HTTP 必须设超时 | assistant `BeanConfig` 3s 连接 / 15s 读取 | LLM 不响应拖死线程池 |
| 12 | 前端不得写裸值 | Stylelint `declaration-property-value-disallowed-list` 拦截颜色/圆角/字号/阴影，间距只允许 8 的倍数 | 破坏双级 token 体系 |

---

## 五、配置矩阵（application.yml 关键项）

| 配置 | 归属 | 值 | 作用 |
|---|---|---|---|
| `goshare.jwt.secret` | 全服务共享 | 同一串 | 网关签发/各服务解析必须一致 |
| `goshare.order.timeout-minutes` | order | 10 | 与 delayLevel=14 对齐 |
| `goshare.order.scheduled-timeout-minutes` | order | 12 | 兜底扫描更宽，避免与 MQ 抢 |
| `goshare.sharding.*` | order | `trip_order` / `passenger_id` / 2 库表 | 分片规则外部化 |
| `goshare.llm.mode` | assistant | `http` | 切 `mock` 可零成本演示 |
| `goshare.llm.data-version` | assistant | 6 | 缓存 key 版本号，改数据即失效 |
| `goshare.llm.model` / `model-large` | assistant | qwen-plus / qwen-max | 双档模型分派 |
| `goshare.weather.mode` | notify | `http` | 切 `mock` 走内置 5 城市 |
| `goshare.ai.*-url` | assistant | `lb://` / 直连 | 服务间调用地址 |

中间件（docker-compose）：MySQL 8.0（root/root123，db `goshare`，`../sql` 挂 initdb）、Redis 7.2、RocketMQ 4.9.7（namesrv + broker + dashboard）、Elasticsearch 8.13.4（单节点、关安全）、Nacos 2.3.2；MinIO 已注释。

---

## 六、测试矩阵（29 类 → 覆盖什么）

| 模块 | 测试类 | 覆盖要点 |
|---|---|---|
| order | `OrderServiceImplTest`（16 例） | 幂等回放、404/400 分支、Lua −1/−2、DB 扣减失败回滚、取消、支付回调幂等（同/异 paymentNo）、关单跳过、超时扫描、候补拒绝/排队、券结算 |
| order | `OrderTimeoutConsumerTest` | 首次消费关单 / 重复消息静默跳过 |
| order | `FeeCalculatorTest`（中文用例名） | 未支付、120 分钟边界、119 分钟、50 封顶、已取消、IN_SERVICE、getter |
| marketing | `CouponServiceTest` | 抢光、重复领唯一索引、别人的券、已用不能锁、满减结算、确认幂等、退回 |
| marketing | `CouponTemplateServiceImplTest` | 时间倒置拒绝、限量初始化 Redis、不限量跳过并默认 7 天、在售列表 |
| marketing | `CouponControllerTest`、`CouponCalculatorTest` | 控制器委托、三种券型计算 |
| trip | `TripServiceImplTest` | 过去时间拒绝、发布初始化座位+索引、ES 空、DB 校准余座、ES 挂降级、详情 404、司机列表、全量同步 |
| trip | `TripEsServiceTest` | 索引/删除失败吞异常、带日期检索、无日期不加时间条件 |
| payment | `PaymentServiceImplTest` | 订单不存在、非 CREATED 拒绝、成功置 SUCCESS、回调失败置 FAILED、按号查不到 |
| user | `UserServiceImplTest`、`DriverCertificationServiceImplTest`、`JwtUtilTest` | 重复注册、BCrypt 落库可校验、默认昵称、密码错/不存在/禁用、实名；认证查重、审核过/驳；JWT 伪造/篡改/非法/角色往返 |
| assistant | `LlmGatewayTest`、`LlmGatewayToolCallTest`、`HttpLlmClientTest`、`MockLlmClientTest` | 缓存命中、规则命中、农历命中、工具通道、前缀与绑定规则、版本化缓存 key、预热；工具调用两轮、不调工具一轮、3 轮上限、工具失败回填、幻觉拦截后重试成功、重试仍幻觉如实降级、如实拒答不误伤；HTTP 解析/重试一次/降级；mock 模板/token 估算 |
| assistant | `LunarServiceTest`、`LunarRuleProviderTest`、`RuleAnswerProviderTest`、`TripWeatherRuleProviderTest` | 三源核实的农历、春节 2027-02-06、节气、干支；规则命中与**不误吞**（"农历年前后有优惠吗"必须不命中）；城市取句首、日期解析、clarify/fetch/ask |
| assistant | `ToolRegistryTest`、`AssistantToolsTest` | 8 工具描述、按名派发、未注册/参数错/异常兜底；空参反问、服务不可用如实告知 |
| assistant | `CancelRetentionServiceTest`、`WorkOrderServiceTest`、`LlmStatsServiceTest`、`NlTripParserTest`、`MatchRerankerTest` | 挽留费用回显+替代方案过滤+幂等+全挂兜底；工单分类/摘要/幂等/截断；统计口径；NL 解析；重排打分 |
| assistant | `BeanConfigTest` | 反射校验两个 RestTemplate 的 3s/15s 超时 |

覆盖率门禁：`server/pom.xml` 的 `check-module` 在 `verify` 阶段逐模块校验 LINE ≥ 0.70，排除 `**/*Application.class`、`config/`、`dto/`、`vo/`；根聚合再校验一次。6 个业务模块均达标（合计 72.2% / 977 行），notify 不在门禁范围（该模块零测试）。

---

## 七、一致性审计：缺陷与不一致清单

> 都是"读代码读出来的"，不是猜的。按影响分级。

### P0 — 会让功能/展示出错

1. **大屏 AI 指标恒为假数据**
   `web/src/screen/ScreenApp.vue:95-96` 读 `d?.total` / `d?.cached`，而 `LlmStatsService.stats()` 返回的是 `totalRequests` / `cacheHitCount`（`LlmStatsService.java:39-40`）。key 对不上 → 永远走 `?? 28` / `?? 21` 兜底，大屏展示的是硬编码数字，面试演示时"省 token 效果"是假的。
   *修法*：前端改读 `totalRequests` / `cacheHitCount`，或后端补别名。

2. **未定义的设计 token 静默失效**
   `MineView.vue:191` `font-size: var(--fs-15)`、`MineView.vue:216` `var(--fs-11)`、`PayView.vue:175` `var(--fs-15)`，而 `tokens.css` 只定义了 12/13/14/16/20/24/32。这三处字号实际不生效（回退继承值），且 Stylelint 的 bare-value 规则管不到 `var(--未定义)` 这种写法。
   *修法*：改成最近的已定义档位（15→16、11→12），或在 tokens 里补齐档位。

### P1 — 死代码 / 单位不一致

3. **`OrderPayView.vue` 是不可达路由**
   `router/index.ts:9` 注册了 `/order/:orderNo`，但全仓库没有任何跳转到它的地方——下单成功后直接 `push('/pay/:orderNo')`。这个页面目前是纯死代码。
   *取舍*：要么接进下单流程（CREATE→待支付页→支付），要么删掉。

4. **金额单位跨页不一致**
   `OrderPayView.vue:64` 用 `fenToYuan(order.payPrice)`，`PayView.vue:87` 和 `MineView.vue:87` 直接 `{{ order.payPrice }}`。后端 `payPrice` 实际是**元**（BigDecimal），所以 `fenToYuan` 那处会把 40 元显示成 0.40 —— 只因为该页不可达才没暴露。
   *修法*：统一成"后端只传元、前端只格式化"，删掉该页的 `fenToYuan`。

5. **"我的"页面是假登录态**
   `MineView.vue:26-27` 注释写明"简化：有 token 视为已登录"，直接 `user.value = { id: 2, phone: '13900139000', role: 'PASSENGER' }`，没有调 `/api/user/me`。真实用户信息（昵称、信任分、角色）拿不到，`trustScore ?? '5.0'` 也就永远 5.0。
   *修法*：有 token 时调 `/api/user/me`，失败再降级。

### P2 — 小瑕疵 / 工程性

6. `ScreenApp.vue` 有一处重复的 `background-clip: text;` 声明。
7. `OrderCreateRequest.idempotentKey` 由前端 `web-${Date.now()}` 生成，只能防双击，不是真正的业务幂等键（服务端幂等靠 `idempotency_key` 表，但键本身不可复用）。
8. `notify-server` 零测试，靠覆盖率门禁"恰好不含它"过关。
9. `scripts/ci.ps1` / `start-service.ps1` 硬编码 `D:\GoShare` 与本机 Maven/JDK 路径，换机器即失效。
10. `NotifyServerApplication` 缺 `scanBasePackages = "com.goshare"`，与其他 7 个服务不一致（当前能跑，属隐患而非 bug）。
11. `go-share-api` 是纯聚合 POM，无任何源码——简历/文档里不要写成"11 个业务模块"。

---

## 八、证据索引（面试可现场翻代码）

| 要讲的点 | 打开这个位置 |
|---|---|
| 防超卖 Lua 原子扣减 | `order/service/impl/OrderServiceImpl.java:64`（DEDUCT_LUA）、`:415`（执行） |
| 还座 Lua | `OrderServiceImpl.java:72`、`:421` |
| 延时消息 10 分钟 | `OrderServiceImpl.java:293`（delayLevel=14 注释与代码） |
| 兜底扫描 12 分钟 | `OrderServiceImpl.java:346`（`@Scheduled(fixedDelay = 60_000)`） |
| 分片规则 | `order/config/ShardingDataSourceConfig.java:62` |
| 退款违约金 20% 封顶 50 | `order/util/FeeCalculator.java:20`、`:26`、`:58`、`:67` |
| 秒杀 Lua | `marketing/service/impl/CouponServiceImpl.java:42`、`:67` |
| 券状态机 SQL | `marketing/mapper/UserCouponMapper.java`（三条 `@Update`） |
| 系统前缀（防幻觉第一层） | `assistant/llm/LlmGateway.java:40` |
| 输出护栏正则 | `LlmGateway.java:46`（HARD_ASSERTION） |
| 工具循环上限 | `LlmGateway.java:64`（MAX_TOOL_ROUNDS=3）、`:251` |
| 答案缓存 TTL | `LlmGateway.java:67`（12h）、`:70`（天气 15min） |
| 夜间预热 | `LlmGateway.java:318`（cron 0 0 3） |
| 网关限流 key | `gateway/config/RateLimitConfig.java:16` |
| 前端设计 token | `web/src/design/tokens.css:34-40`（字号档位） |

---

## 九、缺口与"已知未做"

- **MinIO / Skywalking 未接入**（compose 里 MinIO 注释掉，链路追踪没上）。
- **未用 Seata**：跨服务一致性走"消息 + 幂等 + 状态机 + 定时扫描"的最终一致路线，这是主动取舍不是遗漏，但面试要说清"哪些场景会短暂不一致"（支付成功与订单置 PAID 之间的窗口、券确认与订单 PAID 之间的窗口）。
- **前端是"验证全栈写的基础版"**：有 token 体系、组件库、Storybook、Vitest、E2E，但没有做组件库级工程化（无 changelog、无版本发布、无 a11y 全覆盖）。面试如实说，别吹成"自研组件库"。
- **压测数据只在单机**：场景 A 50 并发抢 3 座、场景 B 100 并发 60×429，都是单机 docker 环境，不能包装成"生产级 QPS"。
- **notify 服务无测试**，`WorkOrder` 相关接口只有 `mock` 造数据的入口（`/work-order/mock`），真实工单从前端没有创建入口。

---

*本文与 `README.md`（阶段进度）、`面试防穿透弹药.md`（问答话术）配合使用：README 看"做到哪一步"，弹药看"怎么答"，本文看"代码在哪、对不对得上"。*
