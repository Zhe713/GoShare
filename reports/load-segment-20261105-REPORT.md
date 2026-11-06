# GoShare 区间库存并发压测报告（场景 C）

- 日期：2026-11-05
- 环境：本机（Windows / Git Bash），8 服务 + MySQL8 + Redis7 运行中，经网关 8000 压测
- 压测方式：自研并发脚本（`scripts/load-test-segment.sh`，curl 50/20 并发 + 数据一致性校验）
  > 说明：JMeter 未安装，本场景用可重复执行的 Bash 并发方案替代，验证目标相同；脚本保留可随时复跑。

## 一、场景设计

| 项 | 值 |
|---|---|
| 行程 | 石家庄→邢台→邯郸（3 站 / 2 区间 / 3 座 / 区间计价 0.30 元/km） |
| 区间 A（0→1） | 50 并发各抢 1 座，仅 3 座可售 |
| 区间 B（1→2） | 20 并发各抢 1 座，仅 3 座可售（A 已满座后执行） |
| 校验 | 成功单数、DB `trip_segment` 余座、Redis 区间 key、分片订单数 |

## 二、结果

| 指标 | 区间 A | 区间 B |
|---|---|---|
| 并发请求 | 50 | 20 |
| 成功（code 200） | **3** | **3** |
| 失败（余票不足） | 47 | 17 |
| 超卖 | **0** | **0** |

一致性校验（行程 tripId=107）：

```
DB trip_segment:   [0,1]=0   [1,2]=0
Redis seg keys:    trip:seg:107:0-1=0   trip:seg:107:1-2=0
orders_created:    6（= 3 成功 + 3 成功，分片表 trip_order_0/1 合计）
```

## 三、结论

1. **零超卖**：区间 A 50 并发抢 3 座，影响行数校验 + Redis 多 key 原子 Lua（先检查全部覆盖区间→统一扣减）+ DB 条件更新兜底，恰好 3 单成功。
2. **区间独立性**：A 满座后 B 仍按自身库存独立售卖（3/3 成功），验证了"区间覆盖计数"替代"全程标量"的核心价值——旧版全程闸门会把 B 误判为无座。
3. **Redis/DB 双写一致**：压测后 Redis 区间 key 与 DB 区间余座完全一致（0/0），无漂移。
4. 失败请求全部返回业务错误（400 余票不足），无 5xx。

## 四、关键实现（对应代码）

- Redis 并发闸门：`OrderServiceImpl.deductSeatRedis` + `DEDUCT_SEGMENTS_LUA`（多 key 先检查后扣减，任一不足整体失败）
- DB 兜底：`TripSegmentMapper.deductSegment`（影响行数必须等于覆盖区间数）
- 区间 key 自愈：`ensureSegmentKeys/initSegmentKeys`（key 丢失按 DB 重建）
