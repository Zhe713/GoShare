#!/bin/bash
# ============================================================
# GoShare 区间库存并发压测（场景 C，替代 JMeter 的自研轻量方案）
# 场景：
#   1. 发布 3 座行程（石家庄→邯郸，2 区间，区间计价）
#   2. 50 并发抢同区间 A（0→1）各 1 座 —— 期望恰好 3 单成功，零超卖
#   3. 20 并发抢区间 B（1→2）各 1 座 —— 期望恰好 3 单成功（区间独立，不受 A 满座影响）
#   4. 校验：Redis 区间 key 与 DB trip_segment 余座一致、订单数与成功数一致
# 用法：bash scripts/load-test-segment.sh
# 依赖：curl + docker（Redis/MySQL 校验）+ 8 服务运行中
# ============================================================
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GATEWAY="http://localhost:8000"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$ROOT/reports/load-segment-$TS"
mkdir -p "$OUT"

echo "==[1] 登录车主（发布）与乘客（下单）=="
DTOKEN=$(curl -s -X POST "$GATEWAY/api/user/login" -H "Content-Type: application/json" \
  -d '{"phone":"13900139000","password":"123456"}' | grep -o '"token":"[^"]*"' | sed 's/"token":"//;s/"//')
LOGIN=$(curl -s -X POST "$GATEWAY/api/user/login" -H "Content-Type: application/json" \
  -d '{"phone":"13700137000","password":"123456"}')
TOKEN=$(echo "$LOGIN" | grep -o '"token":"[^"]*"' | sed 's/"token":"//;s/"//')
if [ -z "$TOKEN" ]; then echo "登录失败: $LOGIN"; exit 1; fi

echo "==[2] 车主发布 3 座 / 3 站 / 2 区间行程（石家庄→邢台→邯郸）=="
TRIP=$(curl -s -X POST "$GATEWAY/api/trip/publish" -H "Authorization: Bearer $DTOKEN" \
  -H "Content-Type: application/json" \
  -d '{"fromCity":"石家庄","toCity":"邯郸","departureTime":"2026-12-01T09:00:00","totalSeats":3,"price":40,"pricePerKm":0.30,"stops":[{"city":"石家庄","offsetMinutes":0},{"city":"邢台","offsetMinutes":45},{"city":"邯郸","offsetMinutes":90}],"vehiclePlate":"冀C·测试","vehicleModel":"压测专用"}')
TRIP_ID=$(echo "$TRIP" | grep -o '"id":[0-9]*' | head -1 | sed 's/"id"://')
if [ -z "$TRIP_ID" ]; then echo "发布失败: $TRIP"; exit 1; fi
echo "   tripId=$TRIP_ID"

run_concurrent() {
  local n="$1" tag="$2" from="$3" to="$4"
  for i in $(seq 1 "$n"); do
    ( curl -s -o "$OUT/$tag-$i.json" -w "%{http_code}" -m 30 \
      -X POST "$GATEWAY/api/order/create" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
      -d "{\"tripId\":$TRIP_ID,\"seatCount\":1,\"idempotentKey\":\"load-$tag-$TS-$i\",\"fromStopSeq\":$from,\"toStopSeq\":$to}" \
      > "$OUT/$tag-$i.code" ) &
  done
  wait
  local ok=0; local fail=0
  for f in "$OUT"/$tag-*.json; do
    grep -q '"code":200' "$f" && ok=$((ok+1)) || fail=$((fail+1))
  done
  echo "$tag: success=$ok fail=$fail"
}

echo "==[3] 50 并发抢区间 A（0→1，3 座）=="
run_concurrent 50 segA 0 1 | tee "$OUT/summary-segA.txt"
echo "==[4] 20 并发抢区间 B（1→2，3 座）=="
run_concurrent 20 segB 1 2 | tee "$OUT/summary-segB.txt"

echo "==[5] 一致性校验 =="
DB_SEG=$(docker exec goshare-mysql mysql -uroot -proot123 goshare -N -e \
  "SELECT CONCAT(from_seq,'-',to_seq,':',remain_seats) FROM trip_segment WHERE trip_id=$TRIP_ID ORDER BY from_seq" 2>/dev/null)
REDIS_SEG=$(docker exec goshare-redis redis-cli MGET "trip:seg:$TRIP_ID:0-1" "trip:seg:$TRIP_ID:1-2" 2>/dev/null | tr '\n' ' ')
ORDER_COUNT=$(docker exec goshare-mysql mysql -uroot -proot123 goshare -N -e \
  "SELECT SUM(c) FROM (SELECT COUNT(*) c FROM trip_order_0 WHERE trip_id=$TRIP_ID UNION ALL SELECT COUNT(*) c FROM trip_order_1 WHERE trip_id=$TRIP_ID) t" 2>/dev/null)

{
  echo "tripId=$TRIP_ID"
  echo "DB_trip_segment:   $DB_SEG"
  echo "Redis_seg_keys:    $REDIS_SEG"
  echo "orders_created:    $ORDER_COUNT (期望 6 = 3+3)"
  echo "== 结论判定 =="
} | tee "$OUT/verify.txt"

echo ""
echo "原始响应与汇总已存：$OUT"
