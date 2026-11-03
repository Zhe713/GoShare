package com.goshare.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.common.result.Result;
import com.goshare.order.dto.CouponSettleResult;
import com.goshare.order.dto.OrderCreateRequest;
import com.goshare.order.dto.OrderVO;
import com.goshare.order.dto.TripDTO;
import com.goshare.order.entity.IdempotencyRecord;
import com.goshare.order.entity.TripOrder;
import com.goshare.order.mapper.IdempotencyRecordMapper;
import com.goshare.order.mapper.TripOrderMapper;
import com.goshare.order.mapper.TripSeatMapper;
import com.goshare.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 订单服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /** Redis 座位库存键前缀 */
    private static final String SEAT_KEY_PREFIX = "trip:seats:";

    /** Redis 候补队列键前缀：waitlist:{行程ID}（有序集合，按登记时间排队） */
    private static final String WAITLIST_KEY_PREFIX = "waitlist:";

    /** 幂等业务类型 */
    private static final String BIZ_CREATE_ORDER = "CREATE_ORDER";
    private static final String BIZ_PAY_CALLBACK = "PAY_CALLBACK";

    /** 原子扣减库存脚本：返回扣减后余量；-1 余票不足，-2 库存未初始化 */
    private static final String DEDUCT_LUA =
            "local cur = tonumber(redis.call('get', KEYS[1])) " +
            "if not cur then return -2 end " +
            "if cur < tonumber(ARGV[1]) then return -1 end " +
            "redis.call('decrby', KEYS[1], ARGV[1]) " +
            "return cur - tonumber(ARGV[1])";

    /** 回补库存脚本：返回回补后余量 */
    private static final String RESTORE_LUA =
            "local cur = tonumber(redis.call('get', KEYS[1])) " +
            "if not cur then return -2 end " +
            "redis.call('incrby', KEYS[1], ARGV[1]) " +
            "return cur + tonumber(ARGV[1])";

    private final TripOrderMapper orderMapper;
    private final IdempotencyRecordMapper idempotencyMapper;
    private final TripSeatMapper tripSeatMapper;
    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${goshare.order.trip-service-url}")
    private String tripServiceUrl;

    @Value("${goshare.order.timeout-minutes}")
    private long timeoutMinutes;

    @Value("${goshare.order.scheduled-timeout-minutes}")
    private long scheduledTimeoutMinutes;

    @Value("${goshare.order.marketing-service-url}")
    private String marketingServiceUrl;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO create(Long passengerId, OrderCreateRequest request) {
        String idemKey = BIZ_CREATE_ORDER + ":" + request.getIdempotentKey();

        // 1. 幂等校验：同一幂等键重复请求直接返回首次结果
        IdempotencyRecord exist = getByIdempotency(idemKey);
        if (exist != null) {
            return parseVO(exist.getResponseJson());
        }

        // 2. 通过 HTTP 调用行程服务校验行程（存在、未出发、可预订）
        TripDTO trip = fetchTrip(request.getTripId());
        if (trip == null) {
            throw new BusinessException(404, "行程不存在");
        }
        if (trip.getStatus() != 0 && trip.getStatus() != 1) {
            throw new BusinessException(400, "行程不可预订");
        }
        if (!trip.getDepartureTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "行程已出发，无法下单");
        }

        // 3. Redis Lua 原子扣减库存（并发闸门）
        long remainAfter = deductSeat(request.getTripId(), request.getSeatCount());
        if (remainAfter == -1) {
            throw new BusinessException(400, "余票不足");
        }
        if (remainAfter == -2) {
            throw new BusinessException(400, "行程库存未初始化");
        }

        String orderNo = null;
        try {
            // 4. DB 条件扣减（双写：Redis 并发闸门 + DB 兜底，防超卖）
            if (tripSeatMapper.deductSeat(request.getTripId(), request.getSeatCount()) == 0) {
                throw new BusinessException(400, "余票不足");
            }
            // 5. 生成订单号，若带券则调用券服务锁定并结算抵扣
            orderNo = generateOrderNo();
            BigDecimal price = trip.getPrice().multiply(BigDecimal.valueOf(request.getSeatCount()));
            BigDecimal discount = BigDecimal.ZERO;
            if (request.getCouponId() != null) {
                CouponSettleResult settle = callCouponSettle(
                        request.getCouponId(), passengerId, orderNo, price);
                discount = settle.getDiscount();
                price = settle.getPayPrice();
            }
            // 6. 创建订单（CREATED 待支付）
            TripOrder order = new TripOrder();
            order.setOrderNo(orderNo);
            order.setTripId(request.getTripId());
            order.setPassengerId(passengerId);
            order.setSeatCount(request.getSeatCount());
            order.setTotalPrice(trip.getPrice().multiply(BigDecimal.valueOf(request.getSeatCount())));
            order.setCouponDiscount(discount);
            order.setCouponId(request.getCouponId());
            order.setPayPrice(price);
            order.setStatus("CREATED");
            orderMapper.insert(order);

            // 7. 发送 RocketMQ 延时消息：待支付订单到期自动关闭（延时 10 分钟，与超时配置一致）
            sendTimeoutDelayMessage(orderNo);

            // 8. 写幂等记录（唯一索引防并发；冲突说明首次请求已成功）
            saveIdempotency(idemKey, BIZ_CREATE_ORDER, order);
            return toVO(order);
        } catch (DuplicateKeyException e) {
            // 并发重复请求：本请求的库存扣减作废，回补（首次请求已成功扣减建单）
            restoreSeat(request.getTripId(), request.getSeatCount());
            // 若本请求已锁定券，一并退回
            if (request.getCouponId() != null && orderNo != null) {
                callCouponReturn(request.getCouponId(), passengerId, orderNo);
            }
            IdempotencyRecord first = getByIdempotency(idemKey);
            if (first != null) {
                return parseVO(first.getResponseJson());
            }
            throw new BusinessException(400, "下单失败，请重试");
        } catch (Exception e) {
            // 业务失败（如 DB 异常）回补 Redis 库存，避免扣了票却没建单
            restoreSeat(request.getTripId(), request.getSeatCount());
            // 若已锁定券，一并退回
            if (request.getCouponId() != null && orderNo != null) {
                callCouponReturn(request.getCouponId(), passengerId, orderNo);
            }
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO cancel(Long passengerId, String orderNo) {
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getPassengerId, passengerId));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (!"CREATED".equals(order.getStatus())) {
            throw new BusinessException(400, "仅待支付订单可以取消");
        }
        order.setStatus("CANCELLED");
        order.setCancelReason("用户主动取消");
        // 分片表更新必须带分片键（passenger_id），否则无法路由
        orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getPassengerId, order.getPassengerId()));
        // 回补库存（Redis + 行程表 DB，双写一致）
        tripSeatMapper.restoreSeat(order.getTripId(), order.getSeatCount());
        restoreSeat(order.getTripId(), order.getSeatCount());
        // 若订单用了券，退回给乘客（尽力而为）
        if (order.getCouponId() != null) {
            callCouponReturn(order.getCouponId(), order.getPassengerId(), orderNo);
        }
        // 座位释放后，候补队列第一位自动补位（释放几个补几个）
        promoteWaitlist(order.getTripId(), order.getSeatCount());
        return toVO(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO handlePayCallback(String orderNo, String paymentNo) {
        // 幂等：同一支付流水号只处理一次
        String idemKey = BIZ_PAY_CALLBACK + ":" + paymentNo;
        IdempotencyRecord exist = getByIdempotency(idemKey);
        if (exist != null) {
            return parseVO(exist.getResponseJson());
        }
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if ("PAID".equals(order.getStatus())) {
            // 已支付且流水号一致：视为重复回调，幂等返回
            if (paymentNo.equals(order.getPaymentNo())) {
                return toVO(order);
            }
            throw new BusinessException(400, "订单状态不允许支付");
        }
        if (!"CREATED".equals(order.getStatus())) {
            throw new BusinessException(400, "订单状态不允许支付");
        }
        order.setStatus("PAID");
        order.setPaymentNo(paymentNo);
        // 分片表更新必须带分片键（passenger_id），否则无法路由
        orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getPassengerId, order.getPassengerId()));
        saveIdempotency(idemKey, BIZ_PAY_CALLBACK, order);
        // 若订单用了券，正式确认用券（尽力而为，券服务内部幂等）
        if (order.getCouponId() != null) {
            callCouponConfirm(order.getCouponId(), order.getPassengerId(), orderNo);
        }
        return toVO(order);
    }

    @Override
    public OrderVO getByOrderNo(String orderNo) {
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return toVOWithTrip(order);
    }

    @Override
    public List<OrderVO> listByPassenger(Long passengerId) {
        return orderMapper.selectList(new LambdaQueryWrapper<TripOrder>()
                        .eq(TripOrder::getPassengerId, passengerId)
                        .orderByDesc(TripOrder::getCreatedAt))
                .stream().map(this::toVOWithTrip).collect(Collectors.toList());
    }

    @Override
    public com.goshare.order.util.FeeCalculator.FeeBreakdown cancelPreview(String orderNo) {
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        // 行程出发时间（判责时间窗）来自行程服务
        TripDTO trip = fetchTrip(order.getTripId());
        if (trip == null) {
            throw new BusinessException(500, "行程服务暂不可用，无法计算费用");
        }
        // 费用一律由 FeeCalculator 纯 Java 计算（金额不靠 LLM）
        return com.goshare.order.util.FeeCalculator.calc(order, trip.getDepartureTime(), LocalDateTime.now());
    }

    /** 发送 RocketMQ 延时消息：待支付订单到期自动关闭（10 分钟后触发） */
    private void sendTimeoutDelayMessage(String orderNo) {
        try {
            // delayLevel=14 对应 RocketMQ 4.9 延时档位 10 分钟（与 goshare.order.timeout-minutes 对齐）
            // 必须设置 TAG=ORDER_TIMEOUT：消费者按 TAG 过滤订阅，不设 tag 的消息会被直接过滤丢弃。
            // 注意：rocketmq-spring 的 Spring Message header（RocketMQHeaders.TAGS="TAGS"）经转换后
            // Broker 侧 Tag 仍为 null（实测 Tags:[null]+CONSUMED_BUT_FILTERED），故改用原生 Message 直接设 Tag。
            org.apache.rocketmq.common.message.Message msg = new org.apache.rocketmq.common.message.Message(
                    "GO_SHARE_ORDER", "ORDER_TIMEOUT", orderNo, orderNo.getBytes(StandardCharsets.UTF_8));
            msg.setDelayTimeLevel(14);
            rocketMQTemplate.getProducer().send(msg, 3_000);
            log.info("已发送订单超时延时消息 orderNo={}", orderNo);
        } catch (Exception e) {
            // 发送失败不阻塞下单：定时扫描（releaseExpiredOrders）兜底关闭
            log.warn("发送超时延时消息失败，交由定时扫描兜底 orderNo={}", orderNo, e);
        }
    }

    /** 按订单号关闭超时订单（供 RocketMQ 消费者调用；状态非 CREATED 时静默跳过） */
    @Override
    public void closeExpiredOrderByNo(String orderNo) {
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo));
        if (order == null) {
            log.warn("超时关闭跳过：订单不存在 orderNo={}", orderNo);
            return;
        }
        closeExpiredOrder(order);
    }

    /** 关闭单个超时订单：状态置 EXPIRED + 回补库存 + 退券 + 候补递补（定时扫描与 MQ 消费共用） */
    private void closeExpiredOrder(TripOrder order) {
        // 已支付/已取消/已关闭的订单无需重复处理（消费防重复的兜底）
        if (!"CREATED".equals(order.getStatus())) {
            return;
        }
        order.setStatus("EXPIRED");
        order.setCancelReason("超时未支付自动关闭");
        // 分片表更新必须带分片键（passenger_id），否则无法路由
        orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getPassengerId, order.getPassengerId()));
        // 回补库存（行程表 DB + Redis，双写一致）
        tripSeatMapper.restoreSeat(order.getTripId(), order.getSeatCount());
        restoreSeat(order.getTripId(), order.getSeatCount());
        // 若订单用了券，退回给乘客（尽力而为）
        if (order.getCouponId() != null) {
            callCouponReturn(order.getCouponId(), order.getPassengerId(), order.getOrderNo());
        }
        // 座位释放后，候补队列第一位自动补位（释放几个补几个）
        promoteWaitlist(order.getTripId(), order.getSeatCount());
        log.info("超时订单自动关闭 orderNo={}", order.getOrderNo());
    }

    /** 每分钟扫描一次（兜底）：关闭超时未支付的订单并回补库存（RocketMQ 延时消息为主路径，兜底晚 2 分钟） */
    @Override
    @Scheduled(fixedDelay = 60_000)
    public int releaseExpiredOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(scheduledTimeoutMinutes);
        List<TripOrder> expiredList = orderMapper.selectList(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getStatus, "CREATED")
                .lt(TripOrder::getCreatedAt, deadline));
        for (TripOrder order : expiredList) {
            try {
                closeExpiredOrder(order);
            } catch (Exception e) {
                log.error("关闭超时订单失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        return expiredList.size();
    }

    // ---------- 候补递补 ----------

    @Override
    public void joinWaitlist(Long passengerId, Long tripId) {
        // 1. 行程必须存在
        TripDTO trip = fetchTrip(tripId);
        if (trip == null) {
            throw new BusinessException(404, "行程不存在");
        }
        // 2. 还有余座时直接提示去下单，不用候补
        if (trip.getRemainSeats() != null && trip.getRemainSeats() > 0) {
            throw new BusinessException(400, "行程还有余座，可直接下单");
        }
        // 3. Redis 有序集合排队（分数=登记时间，先到先得；重复登记幂等覆盖）
        redisTemplate.opsForZSet().add(WAITLIST_KEY_PREFIX + tripId,
                String.valueOf(passengerId), System.currentTimeMillis());
        log.info("候补登记成功 passengerId={} tripId={}", passengerId, tripId);
    }

    @Override
    public void leaveWaitlist(Long passengerId, Long tripId) {
        redisTemplate.opsForZSet().remove(WAITLIST_KEY_PREFIX + tripId, String.valueOf(passengerId));
        log.info("退出候补 passengerId={} tripId={}", passengerId, tripId);
    }

    /** 座位释放后递补：按登记顺序为候补乘客自动下单，释放几个补几个 */
    private void promoteWaitlist(Long tripId, int releasedCount) {
        for (int i = 0; i < releasedCount; i++) {
            Set<String> first = redisTemplate.opsForZSet().range(WAITLIST_KEY_PREFIX + tripId, 0, 0);
            if (first == null || first.isEmpty()) {
                return; // 队列空了
            }
            Long waitUserId = Long.valueOf(first.iterator().next());
            try {
                OrderCreateRequest req = new OrderCreateRequest();
                req.setTripId(tripId);
                req.setSeatCount(1);
                // 固定幂等键：同一行程同一乘客只会被递补一次
                req.setIdempotentKey("WAITLIST:" + tripId + ":" + waitUserId);
                OrderVO vo = create(waitUserId, req);
                redisTemplate.opsForZSet().remove(WAITLIST_KEY_PREFIX + tripId, String.valueOf(waitUserId));
                log.info("候补递补成功 tripId={} waitUserId={} orderNo={}", tripId, waitUserId, vo.getOrderNo());
            } catch (Exception e) {
                // 座位可能刚被他人抢走或下单失败：停止本轮递补，保留排队
                log.warn("候补递补失败 tripId={} waitUserId={}：{}", tripId, waitUserId, e.getMessage());
                return;
            }
        }
    }

    // ---------- 私有方法 ----------

    private long deductSeat(Long tripId, int seatCount) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(DEDUCT_LUA, Long.class);
        return redisTemplate.execute(script,
                List.of(SEAT_KEY_PREFIX + tripId), String.valueOf(seatCount));
    }

    private void restoreSeat(Long tripId, int seatCount) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RESTORE_LUA, Long.class);
        redisTemplate.execute(script,
                List.of(SEAT_KEY_PREFIX + tripId), String.valueOf(seatCount));
    }

    private TripDTO fetchTrip(Long tripId) {
        try {
            Result<TripDTO> result = restTemplate.exchange(
                            tripServiceUrl + "/api/trip/" + tripId,
                            HttpMethod.GET, null,
                            new ParameterizedTypeReference<Result<TripDTO>>() {})
                    .getBody();
            if (result != null && result.getCode() == 200) {
                return result.getData();
            }
            return null;
        } catch (Exception e) {
            throw new BusinessException(500, "行程服务暂不可用，请稍后重试");
        }
    }

    /** 调用券服务：用券结算（锁定券并算抵扣），失败则下单失败 */
    private CouponSettleResult callCouponSettle(Long couponId, Long userId, String orderNo, BigDecimal totalPrice) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of(
                    "couponId", couponId, "userId", userId, "orderNo", orderNo, "totalPrice", totalPrice), headers);
            log.info("调用券服务结算 url={} couponId={} userId={} orderNo={}", marketingServiceUrl, couponId, userId, orderNo);
            Result<CouponSettleResult> result = restTemplate.exchange(
                            marketingServiceUrl + "/api/coupon/settle",
                            HttpMethod.POST, entity,
                            new ParameterizedTypeReference<Result<CouponSettleResult>>() {})
                    .getBody();
            if (result == null || result.getCode() != 200) {
                throw new BusinessException(400, result == null ? "用券失败" : result.getMessage());
            }
            return result.getData();
        } catch (BusinessException e) {
            log.warn("券结算业务失败: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("券结算调用异常", e);
            throw new BusinessException(500, "券服务暂不可用，请稍后重试");
        }
    }

    /** 调用券服务：支付成功确认用券（尽力而为） */
    private void callCouponConfirm(Long couponId, Long userId, String orderNo) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of(
                    "couponId", couponId, "userId", userId, "orderNo", orderNo), headers);
            restTemplate.postForEntity(marketingServiceUrl + "/api/coupon/confirm", entity, Result.class);
        } catch (Exception e) {
            log.warn("确认用券失败 couponId={} orderNo={}", couponId, orderNo, e);
        }
    }

    /** 调用券服务：退券（尽力而为） */
    private void callCouponReturn(Long couponId, Long userId, String orderNo) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of(
                    "couponId", couponId, "userId", userId, "orderNo", orderNo), headers);
            restTemplate.postForEntity(marketingServiceUrl + "/api/coupon/return", entity, Result.class);
        } catch (Exception e) {
            log.warn("退券失败 couponId={} orderNo={}", couponId, orderNo, e);
        }
    }

    private IdempotencyRecord getByIdempotency(String key) {
        return idempotencyMapper.selectOne(new LambdaQueryWrapper<IdempotencyRecord>()
                .eq(IdempotencyRecord::getIdempotentKey, key));
    }

    private void saveIdempotency(String key, String bizType, TripOrder order) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotentKey(key);
        record.setBizType(bizType);
        record.setResponseJson(toJson(toVO(order)));
        record.setExpireAt(LocalDateTime.now().plusDays(1));
        idempotencyMapper.insert(record);
    }

    private OrderVO parseVO(String json) {
        try {
            return objectMapper.readValue(json, OrderVO.class);
        } catch (Exception e) {
            throw new BusinessException(500, "解析幂等结果失败");
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BusinessException(500, "序列化失败");
        }
    }

    /** 订单号：yyyyMMddHHmmss + 6位随机数字 */
    private String generateOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private OrderVO toVO(TripOrder order) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setTripId(order.getTripId());
        vo.setPassengerId(order.getPassengerId());
        vo.setSeatCount(order.getSeatCount());
        vo.setTotalPrice(order.getTotalPrice());
        vo.setCouponDiscount(order.getCouponDiscount());
        vo.setCouponId(order.getCouponId());
        vo.setPayPrice(order.getPayPrice());
        vo.setStatus(order.getStatus());
        vo.setPaymentNo(order.getPaymentNo());
        vo.setCancelReason(order.getCancelReason());
        vo.setCreatedAt(order.getCreatedAt());
        return vo;
    }

    /** 订单 VO + 行程摘要（列表/详情展示：出发城市→到达城市 + 出发时间；行程服务异常时降级为空摘要） */
    private OrderVO toVOWithTrip(TripOrder order) {
        OrderVO vo = toVO(order);
        try {
            TripDTO trip = fetchTrip(order.getTripId());
            if (trip != null) {
                vo.setTripFromCity(trip.getFromCity());
                vo.setTripToCity(trip.getToCity());
                vo.setTripDepartureTime(trip.getDepartureTime());
            }
        } catch (Exception e) {
            log.warn("订单行程摘要获取失败 orderNo={} tripId={}: {}", order.getOrderNo(), order.getTripId(), e.getMessage());
        }
        return vo;
    }
}
