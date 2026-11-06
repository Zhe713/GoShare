# GoShare 深度评审与重写方案

> 评审方式：四线并行代码审计（领域模型 / 交易资金 / 架构工程 / 前端与 AI）+ 真实 HTTP 实测复现。
> 评审对象：`D:\GoShare`，8 个服务、152 个 Java 类、约 8400 行主代码、212 个单测、16 条 E2E。
> 结论时间：2026-11-05，环境全量运行中（8 服务 + 中间件五件套）。

---

## 零、一句话结论

**这不是「顺风车」，这是把 12306 的余票系统套上了顺风车的名字。**

它的工程质量（幂等、Lua 扣减、延时消息、ES 降级、分片、覆盖率门禁）明显高于普通培训项目，
但业务建模停在「固定班次 + 全程一个价 + 全程一个余座计数器」，交易闭环停在「付了钱就结束」。
更关键的是：**支付可以被任何人伪造、券可以被用户自助退回重复使用、任意用户能读别人手机号、任何人都能自己批自己的车主认证**——
这四条不是"考虑不全面"，是**系统当前不具备对外暴露的资格**。

> **进度（2026-11-05）**：阶段 0「止血」已完成并实测验证——6 个攻击入口全部失效，正常支付链路不受影响，
> 后端全量单测 BUILD SUCCESS，Playwright E2E 16/16。详见文末「九、已完成记录」。阶段 1（区间库存）待启动。

---

## 一、实测复现（不是推测，全部跑通）

用真实 HTTP 请求验证，四条 P0 全部成立。

### P0-1 不付一分钱把订单改成"已支付"

```
POST /api/order/pay-callback
Authorization: Bearer <普通乘客自己的 JWT>
{"orderNo":"20261105103009662083","paymentNo":"FAKE-PAYMENT-0001"}

→ http=200 code=200 订单状态=PAID paymentNo=FAKE-PAYMENT-0001
→ GET /api/payment/FAKE-PAYMENT-0001 → code=404（支付流水根本不存在）
```

根因：`OrderController.java:56-60` 的回调接口没有任何调用方鉴权、没有验签、不查 `payment` 表、不校验金额。
网关只判断"有没有有效 JWT"，不区分"支付渠道"和"普通乘客"；而服务端口 8003 直接对宿主机监听，绕过网关连 JWT 都不需要。

**这是全项目最严重的问题**：不仅白嫖，而且资金侧没有任何记录可对账。

### P0-2 同一张券给多个订单抵扣

```
O1 = 下单 2 座 × 45 元 + 券9(满80减15)  → total=90 discount=15 pay=75
POST /api/coupon/return {couponId:9, userId:2, orderNo:O1}  → code=200 成功
O2 = 再下单 2 座 × 45 元 + 同一张券9     → total=90 discount=15 pay=75   ← 券又能用
```

根因：`CouponController.java:51-68` 的 `settle/confirm/return` 是**内部命令**，却被当成用户 API 暴露在网关上，
`userId` 直接取自请求体，没有任何调用方校验。用户可以"锁券 → 自助退券 → 再锁券"无限循环。

### P0-3 已支付订单无法取消，退款能力根本不存在

```
POST /api/order/cancel {orderNo: O1(PAID)} → code=400 "仅待支付订单可以取消"
```

`OrderServiceImpl.java:203-205` 只允许 `CREATED` 取消；`PaymentService` 只有 `pay` 和 `detail`，全仓无 refund。
但前端 `OrderPayView.vue:143-151` 对 `PAID` 展示"取消订单"，挽留抽屉还会算出"预计退款 40 元"——
**用户点完"仍要取消"，得到的是报错**。更糟的是 AI 规则层（`RuleAnswerProvider.java:16`）用官方口吻承诺"退款 1-3 个工作日原路返回"。

说的、做的、承诺的，是三套系统。

### P0-4 任何人可以自己批自己的车主认证 + 读别人手机号

```
POST /api/driver/certification/apply  → certId=2 status=0（待审核）
POST /api/driver/certification/audit  → code=200 status=1（通过，接口无任何鉴权）
重新登录 → role=DRIVER

GET /api/user/1 → 手机号=13800138000 真名=孙哲      ← 任意登录用户可读任意用户
GET /api/user/3 → 手机号=13700137000
```

`DriverCertificationController.java:39-44` 的审核接口没有 `Authorization`、没有角色判断；
`UserController.java:54-60` 的按 ID 查询不做归属校验。顺风车最核心的安全底线（真实司机 + 隐私保护）一条 curl 就穿透。

---

## 二、领域模型：缺的不是字段，是两个本体概念

系统里搜不到 `途经 / 上车点 / segment / 坐标 / 距离` 任何一个词。这不是漏了字段，是**实体选错了**。

### 缺口 1：没有"路线"，只有"城市对"

`trip` 表全部业务字段是：`from_city / to_city / departure_time / total_seats / remain_seats / price`。
顺风车的行程本质是**一条带时间和容量的折线**，途经点是它的固有属性。现在它是一张"大巴班次表"。

### 缺口 2：没有"区间库存"，余座是一个标量

```
DEDUCT_LUA:  KEYS[1] = "trip:seats:{tripId}"   ← 只有一个 key，没有段
DB 兜底:      UPDATE trip SET remain_seats = remain_seats - ? WHERE id = ? AND remain_seats >= ?
```

司机发「北京→上海 4 座」，乘客买「北京→济南」→ 整段少 1 座；反之卖 4 张「北京→济南」，
「济南→上海」直接显示售罄。**同一段路被重复售卖或根本没卖**。

这不是改 SQL 能解决的：区间库存是**区间覆盖计数问题**，要求行程有站点序列、订单绑定上下车点。
而 `trip_order` 表连上下车字段都没有。所以"三层防超卖"防的只是"同一个全程计数器"，与顺风车业务正交。

### 缺口 3：计价公式没有自变量

```
BigDecimal price = trip.getPrice().multiply(BigDecimal.valueOf(seatCount));
```

司机手填的单价 × 座位数。没有里程、没有分段、没有分摊、没有抽成。司机可以给 1000 公里标价 0.01 元——系统不知道那有多远。

### 缺口 4：匹配是精确等值，不是顺路

`TripEsService.java:49-59` 用的是 `fromCity.is(x) AND toCity.is(y)`；`MatchReranker` 注释写"时间/顺路/信任/余座"，
但四个权重里**没有顺路**（时间 .35 / 余座 .25 / 信任 .25 / 价格 .15）。
"北京→天津"的乘客永远匹配不到"北京→上海"的车——而真实顺风车里这才是主要匹配来源。

### 缺口 5：没有履约生命周期

声明的 6 个订单状态里，`IN_SERVICE` 和 `COMPLETED` **全仓零写入**。真实状态机只有：

```
CREATED ──支付──> PAID（终态，没有出口）
   ├──取消──> CANCELLED
   └──超时──> EXPIRED
```

没有"上车确认 / 到达 / 结算 / 互评"。顺风车业务价值的一半在这一段，它被两个建表注释打发了。
连带后果：没有终态 → 没有评价实体 → `trust_score` 只能是默认值 5.0 →
排序里 0.25 的"信任权重"对所有人是常数 → App 上每个司机都显示"信任 5.0"。

### 缺口 6：行程状态机是装饰字段

`trip.status` 一生只写一次（发布时写 0）。没有司机取消、没有开始、没有完成。
司机想撤单？没有接口。满座行程？照样出现在搜索结果里。发车后的行程？只是靠 `departureTime >= now` 被过滤掉。

---

## 三、交易一致性：三个人各写各的，没有裁判

### 竞态：取消 / 支付 / 关单三方互相覆盖

三处状态更新都是"先 select 读状态 → 内存判断 → update by id"，**UPDATE 语句不带 status 条件**：

```java
// 取消（:209-211）、支付回调（:251-253）、关单（:336-338）全是这个形状
orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
        .eq(TripOrder::getId, order.getId())
        .eq(TripOrder::getPassengerId, order.getPassengerId()));   // 没有 .eq(status, "CREATED")
```

后果：**用户付了钱，订单显示已取消，座位已放出**；或反过来，订单已关单但被置为已支付，券已退回还能再用。

### 幂等：先干活后记录，等于没做

```
查幂等 → 扣 Redis → 扣 DB → 插订单 → 发 MQ → 最后才插幂等记录
```

并发同 key 时两个请求都能通过前置检查，第二个在插幂等记录时撞唯一索引，被 catch 后**只回补了 Redis、没有回补 DB**，
异常被吞掉 → 事务正常提交 → **幽灵订单留在库里**。唯一索引保护的是幂等表，不是业务。

### 库存：Redis 可超卖，DB 挡住

`RESTORE_LUA` 无上界（可以超过总座位数），`TripSeatMapper` 的还座 SQL 有 `<= total_seats` 上界。
一次重复还座，DB 被挡住、Redis 继续膨胀 → **Redis 认为有余票、DB 认为没有**。而下单的第一道闸门恰恰是 Redis。

### 超时口径三套

后端 MQ 10 分钟、扫描兜底 12 分钟、前端倒计时 15 分钟。用户在 10~15 分钟之间看到"还剩 5 分钟"，
点支付必然失败。注释还写着"与服务端口径一致"。

### 事务边界：把网络调用包进了数据库事务

`create()` 一个 `@Transactional` 里包含：HTTP 调行程 → Redis Lua → 直接 UPDATE trip 表（跨服务写表）→ HTTP 调券 → insert → 发 MQ。
券服务慢 3 秒，订单库连接就被占 3 秒。而关单方法**连事务都没有**，却被定时任务和 MQ 消费者共用，
且通过 `this.create()` 自调用绕过了 Spring 代理。

---

## 四、架构：8 个服务，只有 4 个是真的

| 服务 | 判断 |
|---|---|
| user | 真上下文 |
| trip | 真上下文，但库存被 order 抢走了一半 |
| marketing | 真上下文（券模板/实例/秒杀），这部分做得最完整 |
| assistant | 真上下文（本项目质量最高的子域） |
| **payment** | **不是独立上下文**，是 order 的一个从属状态机：2 个方法、1 张表、无渠道、无退款 |
| **notify** | **不是通知服务**，是天气 API 代理 + 文案拼装，没有 MQ、短信、推送、模板 |
| **order** | 与 trip 共享库存：直接 `UPDATE trip` 表 |
| gateway | 真网关，但只做认证不做授权 |

**最致命的一条：8 个服务连的是同一个 `goshare` 库。**
order 的 `@Transactional` 之所以"看起来对"，是因为它直接写了 trip 的表（`ShardingDataSourceConfig.java:72` 把 `goshare.trip` 注册成本地单表）。
一旦真的拆库，`create/cancel/close` 三处立刻变成跨服务写，且没有任何 Saga / outbox 兜底。
**这是用架构违规掩盖分布式事务缺失。**

其他架构问题：

- `go-share-api` 是空模块。同一份"行程"在 4 个服务里有 4 份 DTO，同一份"订单"有 3 份，金额类型还不一致（BigDecimal vs String）。
- 3 份 `RestTemplate` 是 `new RestTemplate()`（超时无限），只有 assistant 配了超时。一个慢依赖能拖垮整站。
- `jwt.secret` 在 8 个 yml 里复制 8 遍；DB 密码、LLM Key 明文入库。
- 全仓 0 个 traceId / MDC / micrometer。出了"用户说付了钱订单没变"这种事，**只能靠猜**。
- 21 处 controller 手工解析 Authorization；7 份一模一样的 PingController；3 份单号生成器。
- 分片键是 `passenger_id`，但订单详情/支付回调/关单全部只按 `order_no` 查 → **全路由广播**。分 2 片看不出来，扩到 16 片就是灾难。
- 全仓 0 分页。"我的订单"对每条订单再发一次 HTTP 取行程（N+1）。
- 49 处 `new BusinessException(400, "中文")`，没有错误码体系；所有错误 HTTP 都是 200，监控无法统计真实错误率。

### 测试：212 个用例，没有一个穿过 Spring 事务

100% 是 Mockito 单测，`@SpringBootTest` / Testcontainers / MockMvc 命中 0，并发测试 0，gateway 模块零测试。

最典型的一条：`PaymentServiceImplTest.payCallbackFailureMarksFailed` 断言"回调失败后 payment 被置为 FAILED"，
但生产代码在 `updateById(FAILED)` 之后立刻 `throw`，方法带 `@Transactional(rollbackFor = Exception.class)` ——
**事务回滚，这条 FAILED 记录根本不存在**。这条测试给了团队"失败路径已覆盖"的错觉，实际断言的是一个生产环境不会出现的状态。

真正会出事的 5 条路径（并发幂等、取消/支付竞态、关单/支付竞态、券补偿失败、MQ 重复消费）全部零覆盖。

---

## 五、前端与 AI：一半功能没有入口

### 断头路清单

| 能力 | 后端 | 前端 |
|---|---|---|
| 发布行程 | ✅ | ❌ `publishTrip` 全仓零引用，"发布行程"入口跳到搜索页 |
| 候补 | ✅ | ❌ 无入口 |
| 车主认证 | ✅ | ❌ 无入口 |
| 注册 | ✅ | ❌ 无入口 |
| 工单/客服 | ✅ | ❌ 无入口（首页却写着"工单秒回"） |
| 已支付取消 | ❌ | ✅ 有按钮，点了必报错 |
| 待支付取消 | ✅ | ❌ 待支付订单只能进收银台，收银台没有取消按钮 |
| 评价/到达 | ❌ | ❌ |

**车主侧完成度为 0**：没有车主端任何页面，看不到乘客名单，不能取消行程，看不到收入，连发布都进不去。
一个只有乘客端、没有供给侧 App 的顺风车平台，业务上不成立。

### 下单前，用户看不到任何信任信息

`TripVO` 只有 `id/driverId/fromCity/toCity/departureTime/totalSeats/remainSeats/price/status/remark`。
没有车主昵称头像、没有车牌车型、没有上下车具体地点、没有预计到达时间、没有已拼人数、没有取消政策、没有保险。

用户实际看到的决策信息是"城市 → 城市 + 价格"，与买一张大巴票没有区别。
**顺风车的信任成本全在"谁开车、在哪上车、几点到、出问题怎么办"，这四项全空白。**

### AI：8 个工具里 5 个是日历

`search_trips`、`get_weather`、`get_current_time`，剩下 5 个是农历/节气/干支/生肖/日期计算。
**订单域工具 0 个**——查不了我的订单、算不了取消扣多少钱、下不了单、退不了款。

"语义搜索"名不副实：NL 解析是 `NlTripParser` 里的**正则 + 16 城白名单**，召回是 ES 精确 `is` 匹配，没有分词、没有同义词、没有向量。
它是有价值的（把口语降维成结构化条件），但叫"语义搜索"是包装过度。

**"省 token 四道关卡"逐条验伪**：

| 关卡 | 真相 |
|---|---|
| ① 答案缓存 | 真的，唯一真正降本的。但 key 把完整多轮历史算进 md5，多轮对话几乎必 miss |
| ② 规则先答 | 真的，但是**负资产**：它用官方口吻承诺"退款 1-3 工作日到账"，而系统里没有退款 |
| ③ 固定前缀 | **空实现**：`prefixCheck` 配置全仓零引用，`prefix-check: true` 是死配置 |
| ④ 夜间预生成 | 真的，但预生成的 5 条本来就会被关卡② 免费回答，省不到任何 token |

输出护栏（防幻觉）有一行就能绕过的漏洞：触发条件是 `toolRounds == 0`，
**模型只要随便调一次 `get_current_time`，护栏整体失效**。

---

## 六、真实用户会不会用

### 乘客：**不会**（除非只坐一次看看）

不用它的理由，按重要性排序：

1. **付了钱退不了。** 点取消 → 400；无退款、无客服、无投诉入口。一次意外就是一次资金纠纷。
2. **不知道上谁的车。** 没有车主信息、车牌、车型、上车地点、预计到达。要我在陌生城市上一辆不知道是谁开、什么牌子的车，我不会。
3. **行程结束没有闭环。** 没有到达确认、没有评价，出了问题无法申诉。

会用的理由：

1. 冷门城际（石家庄↔邯郸）能一句话查到真实班次和价格，比翻时刻表快。
2. 价格透明、券能真实抵扣，实付在下单前就确定。
3. 取消前能看到违约金明细（后端纯 Java 计算，不是模型编的）——这是全项目最好的体验点，可惜后端不让取消。

### 车主：**不会**（因为根本没有车主端）

1. 没有车主端页面，看不到谁上了车，不能取消行程，看不到收入。
2. 连发布行程都进不去。
3. 认证没有入口。

会用的理由（补页面就能兑现）：发布校验完整、座位自动扣减不用手动数、满座自动递补候补。

### 平台方：**不会**（合规上过不去）

1. 资金链路不闭合：无退款、PAID 不可取消。
2. AI 用官方口吻输出与真实规则相反的政策，直接制造客诉。
3. 安全：LLM Key 明文入库、`allow-credentials: true` + `allowedOriginPatterns: "*"`、演示账号密码写在 UI placeholder。

---

## 七、重写方案：不是修补，是换骨

### 判断：**保留 40%，重写 60%**

| 必须推倒 | 原因 |
|---|---|
| `OrderServiceImpl` 的 create/cancel/handlePayCallback/closeExpiredOrder/promoteWaitlist | 事务包远程、幂等反模式、状态机竞态、自调用丢事务 |
| `TripSeatMapper` + Sharding 里注册的 `goshare.trip` | 跨服务写表，架构违规 |
| 幂等表用法（后置插入） | 保护不了业务 |
| `PaymentServiceImpl.pay` | 本地事务包远程回调 |
| `CouponController` 的 settle/confirm/return 公网暴露 | 内部命令当用户 API |
| 行程/订单模型（无路线、无区间、无状态机） | 业务本体缺失 |
| 8 份 yml 配置复制 | 改密钥要改 8 个文件 |
| `go-share-api` 空壳 | 契约漂移 |
| 前端收银台 15 分钟硬编码、前端算钱 | 与后端不一致 |

| 可以保留 | 理由 |
|---|---|
| `common` 的 Result / JwtUtil / GlobalExceptionHandler | 结构对，补错误码枚举即可 |
| gateway 的 AuthGlobalFilter + 令牌桶 + key-resolver | 细节到位（限流真的生效，实测 40×200 + 160×429） |
| trip 的 ES 优先 + DB 校准 + Redis 自愈 | 降级设计合理 |
| `CouponCalculator`、`UserCouponMapper` 三条条件 UPDATE | 纯函数 + 状态机写法正确 |
| **assistant 整包**（llm / nl / tool / 护栏 / 历法） | 全项目质量最高，可原样迁移（只需换工具集） |
| `HttpWeatherService` 的诚实降级 | 不编造数据，这个态度要留 |
| `web/src/g-ui` + `design/tokens` + `api/request.ts` | 前端可演进的基础 |
| `FeeCalculator`、`CouponCalculatorTest`、`ToolRegistryTest`、`MatchRerankerTest` | 真正的业务不变量测试 |

### 四个阶段（按"止血 → 换骨 → 补闭环 → 打磨"）

#### 阶段 0：止血（必须最先做，约 1 天）

不做这步，系统不能给任何人看。

1. 内部命令接口从网关路由中摘掉：`pay-callback`、`coupon/settle|confirm|return`、`certification/audit`；服务端口只绑内网。
2. 支付回调改为**校验 `payment` 表**：`paymentNo` 必须存在、`status=SUCCESS`、`order_no` 匹配、`amount == order.payPrice`。
3. 所有状态迁移改 CAS：`UPDATE ... WHERE order_no=? AND status='CREATED'`，影响行数为 0 即冲突。
4. 认证审核加角色校验；`/user/{id}` 加归属校验。
5. 券退回收敛：只允许"订单关闭"事件触发，禁止用户直调。

#### 阶段 1：换骨 —— 领域模型重写（1~2 周）

这是"是不是顺风车"的分水岭。

```
新增 trip_stop（站点序列：seq / city / address / lng / lat / offset_minutes）
     trip_segment（区间库存：trip_id + from_seq + to_seq + remain_seats）
     trip_vehicle_snapshot（发布时快照车牌/车型/颜色）
     order_review（评价）+ driver_rating（派生信任分）
改造 order：绑定 from_stop_seq / to_stop_seq，价格按区间里程算
改造库存：从"一个标量"改为"差分数组 + 区间覆盖计数"（Redis 用 per-segment key，DB 用条件更新）
改造行程状态机：DRAFT → PUBLISHED → FULL → DEPARTED → COMPLETED / CANCELLED
改造订单状态机：CREATED → PAID → BOARDED → COMPLETED → REVIEWED，以及 REFUNDING → REFUNDED
```

配套：`/order/by-trip` 给车主看乘客名单、`/trip/cancel` 给车主撤单（批量退单退座退券通知）。

#### 阶段 2：补闭环 —— 退款域 + 通知域（约 1 周）

```
refund_order（退款单）→ refund_transaction（渠道流水），状态机 APPLYING→APPROVED→REFUNDING→SUCCESS/FAILED
取消/关单只产生退款事件，异步执行并回写
notify 从"天气代理"改成真正的通知服务：MQ 消费者 + 模板 + 渠道适配（先做站内信 + 短信 mock）
对账任务：T+1 比对 payment ↔ order ↔ coupon，差异入 recon_diff 并告警
```

#### 阶段 3：打磨 —— 可观测性 + 前端重构 + AI 重新定位（约 1~2 周）

- 全链路 traceId（网关生成 → RestTemplate 拦截器透传 → MDC），暴露 `/actuator/prometheus`，下单/支付/关单成功率做成指标。
- 前端：重做 4 个页面（行程决策页 / 订单中心 / 个人中心 / 新增车主端 3 页），补 6 个入口（发布、候补、认证、注册、客服、评价）。
- AI：**砍掉 5 个黄历工具**，加 3 个订单工具（查订单 / 算取消扣费 / 下单确认卡），输出改为结构化卡片可点击；
  规则层所有"政策话术"改为从配置读取真实政策，禁止硬编码与业务相反的内容。

### 如果要给面试官讲"为什么重写"

一句话：**原来的系统证明了"我会写微服务"，重写后的系统证明"我懂业务边界"。**

具体可以讲三点：
1. 我发现了"余座是一个标量"这个建模错误，并把它改成区间库存——这是从"能跑"到"对"的跨越。
2. 我把支付回调从公网 API 改成事件 + 校验，把状态迁移从"读-判-写"改成 CAS——这是从"看起来对"到"并发下也对"。
3. 我把 8 个服务里 4 个假服务合并掉（payment→order、notify 重定位），承认"我的微服务拆分过度了"——这是判断力。

---

## 八、AI 功能扩展（按"像天气那样真解决问题"的标准设计）

天气查询之所以是个好功能，是因为它满足四个条件：**数据是真的、结论是确定的、用户当场能用、不依赖 LLM 编**。
按这个标准，下面 6 个功能都能落地，且每个都能讲出工程含量。

### F1 对话式下单（最能打的一个）

**用户说**："帮我订明天下午 6 点去邯郸的，两个人，用那张满 80 减 15 的券"

**做法**：NL → 参数抽取（城市/时间/人数/券）→ 调 `search_trips` 拿真实班次 → 组装**结构化确认卡**
（行程卡 + 券后实付 + 取消政策）→ 用户点"确认下单"才真正调下单接口。

**为什么要这么设计**：LLM 只负责"把话变成参数"，金额、余座、券能不能用全部由后端确定性计算，
AI 永远不碰钱。这正好把项目已有的"防幻觉"从口号变成产品约束。
**工程含量**：Function Calling 多轮编排 + 确认卡协议（`{type:'orderDraft', payload}`）+ 幂等键由参数派生（同一句话重复说不会下两单）。

### F2 出发参谋（比天气更进一步）

**用户问**："我明天 18:00 的车，几点出门？"

**做法**：三个真实数据源合成——发车时间、目的地天气（已有 Open-Meteo）、城市间预估车程（本地距离表）。
输出："17:00 出门，公交约 45 分钟；明天邯郸有小雨，建议多留 10 分钟，17:45 前到集合点。"

**为什么有价值**：天气告诉你"下不下雨"，出发参谋告诉你"几点走"。后者才是决策。
**工程含量**：多工具串联 + 规则兜底（模型不可用时用纯规则也能答）+ 每个数字都可追溯到工具返回。

### F3 价格参谋（这单值不值）

**用户问**："这个价贵吗？"

**做法**：纯 SQL 统计同线路近 30 天订单——均价、最低价、中位数、售罄速度。输出：
"近 30 天石家庄→邯郸均价 48 元，这单 45 元，比均价低 6%；该线路周末通常 2 小时内满座，建议今天下单。"
另可给"等一天再买"的风险提示（基于历史售罄率）。

**为什么有价值**：顺风车用户最大的犹豫就是"会不会买贵了/会不会没座"。
**工程含量**：统计接口 + 确定性话术模板，**零幻觉风险**，而且数据全部来自真实订单表——面试时可以直接打开数据库对照。

### F4 改签建议（把"挽留"升级成"解决方案"）

**现状**：A2 取消挽留只会说"退了要扣 20%"，本质是劝退用户。
**改造**：点取消时，AI 先找同线路 2 小时后的班次，输出："这单取消要扣 8 元；不过 20:30 还有一班同样 45 元，
改签不收违约金，要换吗？" → 一键改签（需要阶段 2 的 `reschedule` 能力）。

**为什么有价值**：挽留是平台视角，改签是用户视角。同一个界面，从"拦着你"变成"帮你解决"。
**工程含量**：订单变更 + 差额结算 + 座位换绑，是真正的交易系统题目。

### F5 行程账单 / 年度总结

**做法**：按月/年聚合用户订单——"这个月拼了 6 次车、共 386 公里、比打车省 412 元、最常走石家庄→邯郸"。
生成可分享的卡片。

**为什么有价值**：这是留存功能，且**数据 100% 真实**（订单表算出来的），比任何 mock 图表都可信。
**工程含量**：聚合查询 + 里程估算 + 可视化；顺带把"我做了个大屏"升级成"我做了个用户愿意分享的东西"。

### F6 节假日出行日历（把"黄历工具"救回来）

原来 5 个农历/节气/干支工具，审计结论是"与出行无关的炫技"。
但它们其实只差一层产品包装：**春节/清明/五一/国庆是顺风车全年最大的出行高峰**。

改造：保留 `lunar-java` 离线历法（0 token 的卖点不丢），把工具从"今年属什么"改成
"距离春节还有 71 天，往年节前 7 天该线路加价 30% 且一票难求，建议提前发布行程"。
工具名从 `get_year_info` 改成 `get_holiday_travel_advice`。

**这样处理的好处**：技术亮点（离线历法、确定性计算、0 token）保住了，产品价值也成立了，
面试时讲的是"我用离线历法算节假日出行高峰"，而不是"我实现了属相查询"。

### 实施顺序与依赖

| 功能 | 依赖 | 工作量 | 建议顺序 |
|---|---|---|---|
| F3 价格参谋 | 无（纯统计现有订单） | 小 | ① 先做，最快见效 |
| F2 出发参谋 | 天气（已有）+ 距离表 | 小 | ② |
| F1 对话式下单 | 需要字段解析 + 确认卡协议 + 前端卡片渲染 | 中 | ③ 最有冲击力 |
| F6 节假日日历 | 改造现有 5 个工具 | 小 | ④ 顺手 |
| F5 行程账单 | 聚合查询 + 页面 | 中 | ⑤ |
| F4 改签建议 | 依赖阶段 2 的订单变更能力 | 大 | ⑥ 压轴 |

**统一原则**：AI 输出一律走"结构化卡片 + 可点击动作"，不再输出一大段纯文本。
所有金额/余座/时间来自后端确定性计算，LLM 只做意图理解和措辞。
这条原则本身就是面试时的加分项——它证明了"AI 产品不是把模型接进来，而是给它划边界"。

---

## 九、已完成记录（阶段 0：止血）

### 做了什么

| # | 改动 | 文件 |
|---|---|---|
| 1 | 新增「内部命令接口」机制：`@InternalApi` + 拦截器 + 自动装配 | `common/security/InternalApi.java`、`InternalApiInterceptor.java`、`common/config/InternalApiAutoConfiguration.java` |
| 2 | 服务间调用自动带 `X-Internal-Token` | `common/security/InternalTokenClientInterceptor.java`，order / payment / notify / assistant 的 `BeanConfig` |
| 3 | 网关屏蔽内部命令路径（返回 404，不暴露接口存在） | `gateway/filter/InternalRouteBlockFilter.java` |
| 4 | 支付回调校验真实流水：存在 + SUCCESS + 同订单 + 金额一致 | `OrderServiceImpl.verifyPayment()` |
| 5 | 所有状态迁移改 CAS（`WHERE status='CREATED'`） | 取消 / 支付回调 / 超时关单三处 |
| 6 | 支付流水先落库再回调；回调失败不再把成功流水改 FAILED | `PaymentServiceImpl.pay()` 去掉 `@Transactional`、重排顺序 |
| 7 | 订单详情 / 取消预览 / 支付动作加归属校验 | `OrderController`、`OrderServiceImpl`、`PaymentServiceImpl` |
| 8 | 新增脱敏公开资料接口，带 PII 的接口收为内部命令 | `UserProfileVO`、`GET /api/user/{id}/profile` |
| 9 | 服务间 RestTemplate 补超时（2s/3s），此前是无限等待 | order / payment / notify `BeanConfig` |
| 10 | 前端登录响应类型修正（后端是扁平 `{token,userId,role}`，原来类型写成嵌套 `user`） | `web/src/api/user.ts`、`MineView.vue` |

### 实测验证（同一套攻击脚本，改前 vs 改后）

| 攻击 | 改前 | 改后 |
|---|---|---|
| 网关伪造支付回调 | 200，订单变 PAID | **404** |
| 网关自助退券 | 200，券退回 | **404** |
| 网关自批车主认证 | 200，角色变 DRIVER | **404** |
| 读他人手机号 | 200，返回手机号+真名 | **403（仅限内部调用）** |
| 直连 8003 伪造回调 | 200，订单变 PAID | **403** |
| 直连 8004 退券 | 200 | **403** |
| 正常下单→支付→查单 | 正常 | **正常（PAID + 真实 paymentNo）** |

回归：后端全量单测 `BUILD SUCCESS`（含 5 处断言按新语义重写）；Playwright E2E 16/16。
