package com.goshare.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.common.result.Result;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    @Value("${goshare.order.trip-service-url}")
    private String tripServiceUrl;

    @Value("${goshare.order.timeout-minutes}")
    private long timeoutMinutes;

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

        try {
            // 4. DB 条件扣减（双写：Redis 并发闸门 + DB 兜底，防超卖）
            if (tripSeatMapper.deductSeat(request.getTripId(), request.getSeatCount()) == 0) {
                throw new BusinessException(400, "余票不足");
            }
            // 5. 创建订单（CREATED 待支付）
            BigDecimal price = trip.getPrice().multiply(BigDecimal.valueOf(request.getSeatCount()));
            TripOrder order = new TripOrder();
            order.setOrderNo(generateOrderNo());
            order.setTripId(request.getTripId());
            order.setPassengerId(passengerId);
            order.setSeatCount(request.getSeatCount());
            order.setTotalPrice(price);
            order.setCouponDiscount(BigDecimal.ZERO);
            order.setPayPrice(price);
            order.setStatus("CREATED");
            orderMapper.insert(order);

            // 6. 写幂等记录（唯一索引防并发；冲突说明首次请求已成功）
            saveIdempotency(idemKey, BIZ_CREATE_ORDER, order);
            return toVO(order);
        } catch (DuplicateKeyException e) {
            // 并发重复请求：另一个请求已扣库存并建单，返回它的首次结果
            IdempotencyRecord first = getByIdempotency(idemKey);
            if (first != null) {
                return parseVO(first.getResponseJson());
            }
            throw new BusinessException(400, "下单失败，请重试");
        } catch (Exception e) {
            // 业务失败（如 DB 异常）回补 Redis 库存，避免扣了票却没建单
            restoreSeat(request.getTripId(), request.getSeatCount());
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
        orderMapper.updateById(order);
        // 回补库存（Redis + 行程表 DB，双写一致）
        tripSeatMapper.restoreSeat(order.getTripId(), order.getSeatCount());
        restoreSeat(order.getTripId(), order.getSeatCount());
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
        orderMapper.updateById(order);
        saveIdempotency(idemKey, BIZ_PAY_CALLBACK, order);
        return toVO(order);
    }

    @Override
    public OrderVO getByOrderNo(String orderNo) {
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return toVO(order);
    }

    @Override
    public List<OrderVO> listByPassenger(Long passengerId) {
        return orderMapper.selectList(new LambdaQueryWrapper<TripOrder>()
                        .eq(TripOrder::getPassengerId, passengerId)
                        .orderByDesc(TripOrder::getCreatedAt))
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    /** 每分钟扫描一次，关闭超时未支付的订单并回补库存 */
    @Override
    @Scheduled(fixedDelay = 60_000)
    public int releaseExpiredOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<TripOrder> expiredList = orderMapper.selectList(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getStatus, "CREATED")
                .lt(TripOrder::getCreatedAt, deadline));
        for (TripOrder order : expiredList) {
            try {
                order.setStatus("EXPIRED");
                order.setCancelReason("超时未支付自动关闭");
                orderMapper.updateById(order);
                // 回补库存（Redis + 行程表 DB，双写一致）
                tripSeatMapper.restoreSeat(order.getTripId(), order.getSeatCount());
                restoreSeat(order.getTripId(), order.getSeatCount());
                log.info("超时订单自动关闭 orderNo={}", order.getOrderNo());
            } catch (Exception e) {
                log.error("关闭超时订单失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        return expiredList.size();
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
        vo.setPayPrice(order.getPayPrice());
        vo.setStatus(order.getStatus());
        vo.setPaymentNo(order.getPaymentNo());
        vo.setCancelReason(order.getCancelReason());
        vo.setCreatedAt(order.getCreatedAt());
        return vo;
    }
}
