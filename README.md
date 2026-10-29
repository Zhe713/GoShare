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
