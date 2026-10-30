# GoShare 顺风拼车平台

个人求职项目（不上线）：以 12306（座位库存 / 并发下单 / 订单支付）和牛券（券引擎 / 结算 / 压测）为蓝本爆改的顺风拼车平台，含 AI 出行助手。

- 后端：Java 17 + Spring Boot 3.2 + Spring Cloud Alibaba（微服务）
- 前端：Vue3 H5（玻璃拟态）+ 管理后台 + 数据大屏
- 测试：单测 / 集成 / 接口 / 并发 / E2E / 压测 / 覆盖率门禁 / CI

## 目录结构

```
GoShare/
├── docs/         # 方案、规范、调研报告（定稿文档）
├── server/       # 后端多模块工程（7 个服务 + 公共模块）
├── web/          # 前端（h5 / admin）
├── sql/          # 数据库脚本（docker 启动时自动执行）
├── docker/       # docker-compose 本地环境
├── scripts/      # 辅助脚本（如 clean.ps1 清理临时文件）
├── reports/      # 测试 / 压测报告
└── _work/        # 一次性临时产物（不入库，随用随清）
```

## 服务与端口

| 服务 | 端口 | 说明 |
|---|---|---|
| go-share-gateway-server | 8000 | 网关（统一入口） |
| go-share-user-server | 8001 | 用户（注册登录 / 实名 / 车主认证） |
| go-share-trip-server | 8002 | 行程（发布 / 座位库存 / 搜索） |
| go-share-order-server | 8003 | 订单（下单占座 / 状态机 / 超时释放） |
| go-share-marketing-server | 8004 | 券（模板 / 领券 / 用券 / 结算） |
| go-share-payment-server | 8005 | 支付（模拟支付 / 回调 / 对账） |
| go-share-notify-server | 8006 | 通知（站内消息 / 行程提醒 / 天气推送） |
| go-share-assistant-server | 8007 | 出行助手（AI 语义搜索 / 取消挽留 / 工单摘要） |

## 快速启动

环境要求：JDK 17、Maven 3.9+、Docker Desktop（Windows）。

```bash
# 1. 启动中间件（MySQL / Redis / RocketMQ / ES / MinIO / Nacos）
cd docker
docker compose up -d

# 2. 编译后端
cd ../server
mvn compile

# 3. 逐个启动服务（或用 IDE 运行）
mvn -pl go-share-user-server spring-boot:run
```

验证：访问 `http://localhost:8001/api/ping` 应返回 `{"code":200,...}`。

## 阶段进度

### 阶段 0：环境与骨架 ✅
- Maven 3.9.16 + 11 模块多模块工程（父 + common + api + gateway + 7 服务）编译通过
- docker-compose 拉起 6 个中间件：MySQL / Redis / RocketMQ 4.9.7（含控制台）/ Elasticsearch / Nacos
- Git 初始化（中文提交规范）、数据库 6 张核心表建表完成

### 阶段 1：核心链路 ✅（注册→登录→发布→搜索→下单→支付→状态流转 全部联调通过）
- 用户服务（8001）：注册即登录、JWT 鉴权、实名认证、车主认证与审核
- 行程服务（8002）：发布行程（Redis 初始化座位库存）、按路线/日期搜索、详情、我的行程
- 订单服务（8003）：下单防重复（幂等键表 + 唯一索引兜底）、Redis Lua 原子扣减 + DB 条件扣减双写、取消/超时自动释放并回补库存（15 分钟定时扫描）
- 支付服务（8005）：模拟支付、回调订单服务标记已支付（回调幂等）、重复支付拦截
- 联调验证 12 项全部通过：幂等重复下单返回首次结果、余票不足拦截、取消回补、超时释放（EXPIRED+库存回补）、重复支付拦截

### 阶段 2：优惠券服务 ✅（模板→领券→秒杀→锁定用券→支付确认→取消退券 全部联调通过）
- 营销服务（8004）：券模板管理（满减 / 折扣 / 立减三种类型）、领券（每人限领一张，唯一索引兜底）、秒杀（Redis Lua 原子扣减做闸门 + DB 条件更新 received_count 兜底防超发）、我的券列表
- 结算引擎（纯 Java，可单测）：满减需达门槛否则不抵扣、立减无门槛、折扣按总价 × 折扣率四舍五入、抵扣不超订单总额
- 订单服务（8003）接入用券：下单带券 → 调券服务锁定并结算（couponDiscount / payPrice 落库）→ 支付成功确认用券（券状态 0→1）→ 取消 / 超时 / 下单异常退券（状态 3→0）
- 券变动全量流水（coupon_log：RECEIVE / LOCK / USE / RETURN），便于审计
- 联调验证 9 项全部通过：3 种券结算金额正确（满 50 减 10 / 8.5 折抵 9 元 / 立减）、重复领取被拦、秒杀限量 2 第 3 人抢光、重复领/DB 已满时 Redis 库存回补、支付后券确认已用、取消后券退回、已用券再下单被拦（"券不可用"）

### 阶段 3：微服务治理 + 搜索 + 候补 ✅（网关统一入口 / 搜索可用 / 候补自动补位 全部联调通过）
- **Nacos 注册中心**：8 个服务全部注册（gateway/user/trip/order/marketing/payment/notify/assistant），服务间调用改服务名 lb:// 负载均衡（带券下单、支付回调跨服务链路验证通过）
- **Gateway 统一入口（8000）**：7 条路由全部指向服务名，全局 JWT 鉴权（白名单外必须携带 Bearer token，透传 X-User-Id）、Redis 令牌桶限流（单 IP 20 qps / 突发 40）、统一 401/限流返回格式
- **ES 行程搜索**：行程发布同步到 Elasticsearch（goshare_trip 索引，出发时间秒级格式），搜索 ES 优先 + 数据库实时余座校准，ES 不可用自动降级 MySQL；提供全量同步运维接口
- **候补递补**：行程满座后乘客可排队候补（Redis 有序集合按登记时间排序，重复登记幂等）；订单取消 / 超时释放座位后，候补第一位自动生成订单补位（固定幂等键防重复递补）
- **分库分表（ShardingSphere-JDBC 5.5.0）**：订单表按乘客ID取模路由到 trip_order_0 / trip_order_1（同库分表演示），主键由分片引擎雪花算法生成，未分片表走默认数据源；更新必须携带分片键；老数据已迁移、原表改名 trip_order_legacy 兜底
- 阶段 3 联调验证：Nacos 服务数 8、网关登录 / 伪造 token 401、限流拦截、ES 搜索（石家庄→北京命中、反向为空、日期过滤）、候补递补（满座登记 → 取消释放 → 自动补位 → 队列清空）、分片路由（乘客ID 2→表0 / 3→表1、雪花主键、跨片查询、支付后状态更新）

### 阶段 4：RocketMQ 延时消息超时释放 + 消费防重复 + JMeter 压测 ✅（验收口径：不超卖 + 有压测报告）
- **RocketMQ 延时消息超时释放（主链路）**：下单成功后发送 10 分钟延时消息（`GO_SHARE_ORDER` topic、Tag=`ORDER_TIMEOUT`、delayLevel=14 对应 10 分钟档，Keys 携带订单号），到期后消费者监听 Tag 过滤收到消息 → 调 `closeExpiredOrderByNo` 关单（状态 CREATED→EXPIRED + 分片表更新 + DB/Redis 双回补余座 + 退券 + 候补递补）
- **消费防重复（两道防线）**：① Redis `setIfAbsent("order:timeout:consume:{orderNo}", 1h)` 消费幂等键；② `closeExpiredOrderByNo` 内订单非 CREATED 状态静默跳过（状态机防重复）
- **定时扫描兜底**：12 分钟定时扫描（比延时消息晚 2 分钟）兜底关单，防止消息丢失或消费失败导致订单永久悬挂（与 MQ 消费共用同一个关闭函数）
- **JMeter 压测（JMeter 5.6.3，非 GUI 模式）**：
  - 场景A（不超卖）：50 线程并发直连订单服务 8003 抢 3 个座位 → 全部 HTTP 200，**DB 实际建单 3 单、余座 0、无超卖**（Redis Lua 原子扣减 + DB 条件更新 + 幂等键三重复防线）
  - 场景B（网关限流）：100 线程并发走网关 8000 → **60 个 429（Too Many Requests）被 Sentinel 限流拒绝**，40 个放行（阈值 20qps/突发 40），放行请求进入业务层后仍受余座防线约束
  - 压测脚本：`jmeter/jmeter-sceneA-nosellout.jmx`、`jmeter/jmeter-sceneB-gateway-limit.jmx`；完整结论见 `reports/阶段4压测报告.md`
- 阶段 4 链路验证：下单 → MQ 延时消息发送（日志确认）→ 10 分钟后消费者消费 → 订单 EXPIRED + Redis 消费去重键 + 余座回补 3 → 兜底定时扫描（12 分钟）在消息路径异常时仍可关单（历史验证）

## 阶段 1 接口速查

| 服务 | 接口 | 说明 |
|---|---|---|
| user | POST /api/user/register | 注册（直接返回 token） |
| user | POST /api/user/login | 登录 |
| user | GET /api/user/me | 当前用户（需 Bearer token） |
| user | POST /api/user/real-name-verify | 实名认证 |
| user | POST /api/driver/certification/apply | 车主认证申请 |
| user | POST /api/driver/certification/audit | 认证审核（通过后角色变 DRIVER） |
| trip | POST /api/trip/publish | 发布行程（仅车主） |
| trip | GET /api/trip/search?fromCity=&toCity=&departDate= | 搜索行程 |
| trip | GET /api/trip/{tripId} | 行程详情 |
| trip | GET /api/trip/mine | 我发布的行程 |
| order | POST /api/order/create | 下单（需幂等键） |
| order | POST /api/order/cancel | 取消（回补库存） |
| order | POST /api/order/pay-callback | 支付回调（内部，幂等） |
| order | GET /api/order/{orderNo} | 订单详情 |
| order | GET /api/order/mine | 我的订单 |
| payment | POST /api/payment/pay | 发起模拟支付 |
| payment | GET /api/payment/{paymentNo} | 支付结果 |

## 阶段 2 接口速查

| 服务 | 接口 | 说明 |
|---|---|---|
| marketing | POST /api/coupon/template/create | 创建券模板（type: FULL_REDUCTION / DISCOUNT / CASH） |
| marketing | GET /api/coupon/template/list | 券模板列表 |
| marketing | POST /api/coupon/receive | 领券 / 秒杀抢券（body: templateId） |
| marketing | GET /api/coupon/mine | 我的券（可按 status 筛选：0未用 1已用 2过期 3锁定） |
| marketing | POST /api/coupon/settle | 用券结算（内部：锁定券 + 算抵扣） |
| marketing | POST /api/coupon/confirm | 确认用券（内部：支付成功后调用，幂等） |
| marketing | POST /api/coupon/return | 退券（内部：取消/超时/下单失败调用，幂等） |
| order | POST /api/order/create | 下单（body 可带 couponId，锁定券并结算抵扣） |

注：coupon_id 字段通过 `sql/02-coupon-schema.sql` 末尾动态 ALTER 添加（MySQL 8 兼容写法）。

## 阶段 3 接口速查

| 服务 | 接口 | 说明 |
|---|---|---|
| gateway | 所有 /api/** | 统一入口 8000：JWT 鉴权 + 限流后转发到各服务 |
| trip | POST /api/trip/es-sync | ES 全量同步行程（运维接口） |
| order | POST /api/order/waitlist | 候补登记（行程满座时排队，body: tripId） |
| order | POST /api/order/waitlist/leave | 退出候补（body: tripId） |

注：订单表分片脚本见 `sql/03-sharding-schema.sql`（建分片表 + 数据迁移 + 老表改名兜底），分片规则在 `ShardingDataSourceConfig.java`（Java API 手动装配，5.3+ 无独立 Starter）。

## Git 提交规范（中文）

所有提交信息使用中文，格式为 `类型(模块)：中文描述`：

| 类型 | 含义 | 示例 |
|---|---|---|
| feat | 新功能 | `feat(订单)：新增下单占座接口` |
| fix | 修复缺陷 | `fix(行程)：修复座位扣减并发问题` |
| docs | 文档 | `docs(方案)：更新技术清单到 v2.0` |
| test | 测试 | `test(订单)：补充状态机单测` |
| refactor | 重构 | `refactor(券)：抽取结算责任链` |
| chore | 杂项 | `chore(工程)：升级依赖版本` |

## 文件纪律

- 所有生成物放在 `GoShare` 目录内；临时产物放 `_work/`，用完即清
- 清理：运行 `scripts/clean.ps1`（清 target / 日志 / 临时目录，可带 `-DockerDown` 连容器一起停）
