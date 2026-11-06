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
import com.goshare.order.entity.OrderReview;
import com.goshare.order.entity.OrderStatus;
import com.goshare.order.entity.RefundOrder;
import com.goshare.order.entity.RefundTransaction;
import com.goshare.order.entity.TripOrder;
import com.goshare.order.mapper.IdempotencyRecordMapper;
import com.goshare.order.mapper.OrderReviewMapper;
import com.goshare.order.mapper.PriceStatsMapper;
import com.goshare.order.mapper.RefundOrderMapper;
import com.goshare.order.mapper.RefundTransactionMapper;
import com.goshare.order.mapper.TripOrderMapper;
import com.goshare.order.mapper.TripSeatMapper;
import com.goshare.order.mapper.TripSegmentMapper;
import com.goshare.order.service.OrderService;
import com.goshare.order.util.FeeCalculator;
import com.goshare.order.util.FeeCalculator.FeeBreakdown;
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

    /** Redis 区间库存键前缀（阶段1区间版并发闸门）：trip:seg:{行程ID}:{fromSeq}-{toSeq} */
    private static final String SEG_KEY_PREFIX = "trip:seg:";

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

    /**
     * 多区间原子扣减脚本（阶段1区间版并发闸门）。
     * KEYS = 订单覆盖的所有区间 key；ARGV[1] = 座位数。
     * 先检查所有 key 余座都充足，再统一扣减——任一不足则整体失败（-1），杜绝「扣了前段后段不足」的半扣状态。
     */
    private static final String DEDUCT_SEGMENTS_LUA =
            "for i, key in ipairs(KEYS) do " +
            "  local cur = tonumber(redis.call('get', key)) " +
            "  if not cur then return -2 end " +
            "  if cur < tonumber(ARGV[1]) then return -1 end " +
            "end " +
            "for i, key in ipairs(KEYS) do " +
            "  redis.call('decrby', key, ARGV[1]) " +
            "end " +
            "return 1";

    /** 多区间回补脚本：全部 incrby（无上界，由 DB 条件更新兜底防重复回补超发） */
    private static final String RESTORE_SEGMENTS_LUA =
            "for i, key in ipairs(KEYS) do " +
            "  redis.call('incrby', key, ARGV[1]) " +
            "end " +
            "return 1";

    private final TripOrderMapper orderMapper;
    private final IdempotencyRecordMapper idempotencyMapper;
    private final TripSeatMapper tripSeatMapper;
    private final TripSegmentMapper tripSegmentMapper;
    private final RefundOrderMapper refundOrderMapper;
    private final RefundTransactionMapper refundTransactionMapper;
    private final OrderReviewMapper orderReviewMapper;
    private final PriceStatsMapper priceStatsMapper;
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

    @Value("${goshare.order.payment-service-url:http://go-share-payment-server}")
    private String paymentServiceUrl;

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

        // 3. 归一化上下车站点（区间库存；未传则退化为全程 [0, last]）
        int[] seg = resolveSegment(trip, request.getFromStopSeq(), request.getToStopSeq());
        int fromSeq = seg[0];
        int toSeq = seg[1];

        // 4. Redis 原子扣减库存（并发闸门·区间感知）：
        //    有区间数据 → per-segment 多 key Lua「先检查后扣减」；
        //    无区间数据 → 旧全程标量 key。区间满座不再误拦其他区间的订单。
        long remainAfter = deductSeatRedis(request.getTripId(), fromSeq, toSeq, request.getSeatCount());
        if (remainAfter == -1) {
            throw new BusinessException(400, "余票不足");
        }
        if (remainAfter == -2) {
            throw new BusinessException(400, "行程库存未初始化");
        }

        String orderNo = null;
        try {
            // 5. DB 条件扣减（双写：Redis 并发闸门 + DB 兜底，防超卖）
            if (!deductSeatDb(request.getTripId(), fromSeq, toSeq, request.getSeatCount())) {
                throw new BusinessException(400, "余票不足");
            }
            // 5. 生成订单号，若带券则调用券服务锁定并结算抵扣
            orderNo = generateOrderNo();
            BigDecimal price = calcOrderPrice(trip, fromSeq, toSeq, request.getSeatCount());
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
            order.setFromStopSeq(fromSeq);
            order.setToStopSeq(toSeq);
            order.setDistanceKm(calcSegmentDistance(trip, fromSeq, toSeq));
            order.setTotalPrice(calcOrderPrice(trip, fromSeq, toSeq, request.getSeatCount()));
            order.setCouponDiscount(discount);
            order.setCouponId(request.getCouponId());
            order.setPayPrice(price);
            order.setStatus(OrderStatus.CREATED);
            // 显式写入创建时间：DB 的 DEFAULT CURRENT_TIMESTAMP 不会回填到实体，
            // 不设的话下单响应里 createdAt 恒为 null（前端展示/倒计时都要用它）
            order.setCreatedAt(LocalDateTime.now());
            order.setUpdatedAt(order.getCreatedAt());
            orderMapper.insert(order);

            // 7. 发送 RocketMQ 延时消息：待支付订单到期自动关闭（延时 10 分钟，与超时配置一致）
            sendTimeoutDelayMessage(orderNo);

            // 8. 写幂等记录（唯一索引防并发；冲突说明首次请求已成功）
            //    返回与快照都带行程摘要，保证首次响应与幂等回放完全一致
            OrderVO vo = toVOWithTrip(order);
            saveIdempotency(idemKey, BIZ_CREATE_ORDER, vo);
            return vo;
        } catch (DuplicateKeyException e) {
            // 并发重复请求：本请求的 Redis 扣减作废回补（DB 扣减与建单同事务，随异常回滚，无需手动回补 DB）
            restoreSegmentRedisOnly(request.getTripId(), fromSeq, toSeq, request.getSeatCount());
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
            // 业务失败（如 DB 异常）回补 Redis 库存，避免扣了票却没建单；DB 由事务回滚
            restoreSegmentRedisOnly(request.getTripId(), fromSeq, toSeq, request.getSeatCount());
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
        // 已支付订单取消 → 走退款闭环（阶段2：不再直接报错，而是产生退款单）
        if (OrderStatus.PAID.equals(order.getStatus()) || OrderStatus.BOARDED.equals(order.getStatus())) {
            return cancelPaidOrder(order);
        }
        if (!OrderStatus.CREATED.equals(order.getStatus())) {
            throw new BusinessException(400, "当前订单状态不可取消");
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelReason("用户主动取消");
        // 分片表更新必须带分片键（passenger_id），否则无法路由
        // CAS：把 status='CREATED' 作为更新条件，防止与支付回调/超时关单并发时互相覆盖
        int rows = orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getPassengerId, order.getPassengerId())
                .eq(TripOrder::getStatus, OrderStatus.CREATED));
        if (rows == 0) {
            // 读到 CREATED 但更新失败 = 期间被支付回调或关单任务改了状态
            throw new BusinessException(409, "订单状态已变化（可能已支付或已超时），请刷新后重试");
        }
        // 回补库存（Redis + 行程表/区间表 DB，双写一致）
        restoreSegmentOrSeat(order.getTripId(), safeFromSeq(order), safeToSeq(order), order.getSeatCount());
        // 若订单用了券，退回给乘客（尽力而为）
        if (order.getCouponId() != null) {
            callCouponReturn(order.getCouponId(), order.getPassengerId(), orderNo);
        }
        // 座位释放后，候补队列第一位自动补位（释放几个补几个）
        promoteWaitlist(order.getTripId(), order.getSeatCount());
        // 取消事件（通知服务生成站内信，阶段2）
        sendOrderEvent("ORDER_CANCELLED", order.getPassengerId(), order.getOrderNo(), null);
        return toVO(order);
    }

    /**
     * 已支付订单取消：产生退款单并异步执行退款（阶段2退款闭环）。
     *
     * <p>这是"付了钱退不了"这个 P0 的核心修复。取消不再报错，而是：
     * ① 按 FeeCalculator 算违约金与退款额；② 状态迁移 PAID → REFUNDING（CAS）；
     * ③ 落退款单 + 退款流水；④ 回补库存退券；⑤ 同步调用支付服务退款，成功后 REFUNDED。
     */
    private OrderVO cancelPaidOrder(TripOrder order) {
        // 费用由 FeeCalculator 纯 Java 计算（金额不靠 LLM，与取消挽留预览口径一致）
        TripDTO trip = fetchTrip(order.getTripId());
        if (trip == null) {
            throw new BusinessException(500, "行程服务暂不可用，无法计算退款");
        }
        FeeBreakdown fee = FeeCalculator.calc(order, trip.getDepartureTime(), LocalDateTime.now());

        // CAS：PAID/BOARDED → REFUNDING，防止并发重复取消/退款
        String fromStatus = order.getStatus();
        order.setStatus(OrderStatus.REFUNDING);
        order.setCancelReason("用户取消（退款中）");
        int rows = orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getPassengerId, order.getPassengerId())
                .eq(TripOrder::getStatus, fromStatus));
        if (rows == 0) {
            throw new BusinessException(409, "订单状态已变化，取消被拒绝");
        }

        // 落退款单（幂等：uk_order_no 唯一，重复取消会撞唯一索引）
        String refundNo = generateRefundNo();
        RefundOrder refund = new RefundOrder();
        refund.setRefundNo(refundNo);
        refund.setOrderNo(order.getOrderNo());
        refund.setPassengerId(order.getPassengerId());
        refund.setPaymentNo(order.getPaymentNo());
        refund.setRefundAmount(fee.refundAmount);
        refund.setFeeAmount(fee.penalty);
        refund.setReason("用户取消订单");
        refund.setStatus(RefundOrder.STATUS_REFUNDING);
        refundOrderMapper.insert(refund);

        // 落退款流水
        RefundTransaction tx = new RefundTransaction();
        tx.setRefundNo(refundNo);
        tx.setOrderNo(order.getOrderNo());
        tx.setPaymentNo(order.getPaymentNo());
        tx.setChannel("MOCK");
        tx.setAmount(fee.refundAmount);
        tx.setStatus("PENDING");
        refundTransactionMapper.insert(tx);

        // 回补库存 + 退券 + 候补递补
        restoreSegmentOrSeat(order.getTripId(), safeFromSeq(order), safeToSeq(order), order.getSeatCount());
        if (order.getCouponId() != null) {
            callCouponReturn(order.getCouponId(), order.getPassengerId(), order.getOrderNo());
        }
        promoteWaitlist(order.getTripId(), order.getSeatCount());

        // 同步调用支付服务执行退款（演示渠道 MOCK 立即成功）
        boolean refundOk = callPaymentRefund(order.getPaymentNo(), fee.refundAmount);
        if (refundOk) {
            // 退款成功：订单 REFUNDING → REFUNDED，退款单 SUCCESS
            order.setStatus(OrderStatus.REFUNDED);
            order.setCancelReason("用户取消（已退款）");
            orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                    .eq(TripOrder::getId, order.getId())
                    .eq(TripOrder::getPassengerId, order.getPassengerId())
                    .eq(TripOrder::getStatus, OrderStatus.REFUNDING));
            refund.setStatus(RefundOrder.STATUS_SUCCESS);
            refund.setRefundedAt(LocalDateTime.now());
            refundOrderMapper.updateById(refund);
            tx.setStatus("SUCCESS");
            tx.setChannelRefNo("MOCK-REFUND-" + System.currentTimeMillis());
            refundTransactionMapper.updateById(tx);
            // 退款成功事件（通知服务生成站内信，阶段2）
            sendOrderEvent("REFUND_SUCCESS", order.getPassengerId(), order.getOrderNo(), fee.refundAmount);
            log.info("订单退款成功 orderNo={} refundNo={} amount={}", order.getOrderNo(), refundNo, fee.refundAmount);
        } else {
            // 退款失败：订单保持 REFUNDING，退款单 FAILED，由对账/重试任务兜底
            refund.setStatus(RefundOrder.STATUS_FAILED);
            refundOrderMapper.updateById(refund);
            tx.setStatus("FAILED");
            refundTransactionMapper.updateById(tx);
            log.warn("订单退款失败，待重试 orderNo={} refundNo={}", order.getOrderNo(), refundNo);
        }
        OrderVO vo = toVO(order);
        vo.setRefundNo(refundNo);
        vo.setRefundAmount(fee.refundAmount);
        return vo;
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
        if (!OrderStatus.CREATED.equals(order.getStatus())) {
            throw new BusinessException(400, "订单状态不允许支付");
        }
        // 关键：回调必须能证明"钱真的收到过"，不再信任请求体
        verifyPayment(orderNo, paymentNo, order);
        order.setStatus(OrderStatus.PAID);
        order.setPaymentNo(paymentNo);
        // CAS：并发下（支付回调 vs 超时关单 vs 用户取消）只有一个能成功
        int rows = orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getPassengerId, order.getPassengerId())
                .eq(TripOrder::getStatus, OrderStatus.CREATED));
        if (rows == 0) {
            throw new BusinessException(409, "订单状态已变化，支付回调被拒绝");
        }
        OrderVO paidVo = toVO(order);
        saveIdempotency(idemKey, BIZ_PAY_CALLBACK, paidVo);
        // 若订单用了券，正式确认用券（尽力而为，券服务内部幂等）
        if (order.getCouponId() != null) {
            callCouponConfirm(order.getCouponId(), order.getPassengerId(), orderNo);
        }
        // 订单支付成功事件（通知服务生成站内信，阶段2）
        sendOrderEvent("ORDER_PAID", order.getPassengerId(), orderNo, order.getPayPrice());
        return paidVo;
    }

    /**
     * 校验支付流水真实性（防伪造回调）。
     *
     * <p>旧实现只信任请求体里的 orderNo + paymentNo，任何人构造一个请求就能把订单改成已支付，
     * 而 payment 表里根本没有这条流水（实测可复现）。这里改为向支付服务取流水并核对三件事：
     * 流水存在且 SUCCESS、流水归属本订单、金额与订单实付完全一致。
     */
    private void verifyPayment(String orderNo, String paymentNo, TripOrder order) {
        if (paymentNo == null || paymentNo.isBlank()) {
            throw new BusinessException(400, "支付流水号不能为空");
        }
        Result<Map<String, Object>> resp;
        try {
            resp = restTemplate.exchange(paymentServiceUrl + "/api/payment/" + paymentNo,
                    HttpMethod.GET, HttpEntity.EMPTY,
                    new ParameterizedTypeReference<Result<Map<String, Object>>>() {
                    }).getBody();
        } catch (Exception e) {
            log.warn("校验支付流水失败 orderNo={} paymentNo={}", orderNo, paymentNo, e);
            throw new BusinessException(500, "支付服务暂不可用，无法校验支付流水");
        }
        Map<String, Object> data = resp == null ? null : resp.getData();
        if (data == null) {
            throw new BusinessException(400, "支付流水不存在，拒绝回调");
        }
        if (!"SUCCESS".equals(String.valueOf(data.get("status")))) {
            throw new BusinessException(400, "支付流水未成功，拒绝回调");
        }
        if (!orderNo.equals(String.valueOf(data.get("orderNo")))) {
            throw new BusinessException(400, "支付流水与订单不匹配，拒绝回调");
        }
        Object amount = data.get("amount");
        if (amount == null || order.getPayPrice() == null
                || new BigDecimal(String.valueOf(amount)).compareTo(order.getPayPrice()) != 0) {
            throw new BusinessException(400, "支付金额与订单金额不一致，拒绝回调");
        }
        log.info("支付流水校验通过 orderNo={} paymentNo={} amount={}", orderNo, paymentNo, amount);
    }

    @Override
    public OrderVO getByOrderNo(Long passengerId, String orderNo) {
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        // 订单详情仅本人可见（此前任何登录用户遍历单号即可读取他人订单与金额）
        if (passengerId != null && !passengerId.equals(order.getPassengerId())) {
            throw new BusinessException(403, "无权查看他人订单");
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
    public com.goshare.order.util.FeeCalculator.FeeBreakdown cancelPreview(Long passengerId, String orderNo) {
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (passengerId != null && !passengerId.equals(order.getPassengerId())) {
            throw new BusinessException(403, "无权查看他人订单");
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
        if (!OrderStatus.CREATED.equals(order.getStatus())) {
            return;
        }
        order.setStatus(OrderStatus.EXPIRED);
        order.setCancelReason("超时未支付自动关闭");
        // CAS：只有仍是 CREATED 才关单。若期间支付回调已把它置为 PAID，这里影响行数为 0，
        // 必须立刻退出——否则会把"已支付订单"改成已关闭并回补座位（钱收了、座位放了）。
        int rows = orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getPassengerId, order.getPassengerId())
                .eq(TripOrder::getStatus, OrderStatus.CREATED));
        if (rows == 0) {
            log.info("超时关单跳过：订单状态已被并发修改 orderNo={}", order.getOrderNo());
            return;
        }
        // 回补库存（行程表/区间表 DB + Redis，双写一致）
        restoreSegmentOrSeat(order.getTripId(), safeFromSeq(order), safeToSeq(order), order.getSeatCount());
        // 若订单用了券，退回给乘客（尽力而为）
        if (order.getCouponId() != null) {
            callCouponReturn(order.getCouponId(), order.getPassengerId(), order.getOrderNo());
        }
        // 座位释放后，候补队列第一位自动补位（释放几个补几个）
        promoteWaitlist(order.getTripId(), order.getSeatCount());
        // 超时事件（通知服务生成站内信，阶段2）
        sendOrderEvent("ORDER_EXPIRED", order.getPassengerId(), order.getOrderNo(), null);
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

    // ---------- 区间库存（阶段1「换骨」） ----------

    /**
     * 归一化上下车站点序号：未传则退化为全程 [0, last]。
     * 站点数 = stops.size()；stops 为 null 或空列表（老行程无站点记录）时按两站 [0,1] 处理，
     * 避免 size=0 → last=-1 → clamp 出负数序号。
     */
    private int[] resolveSegment(TripDTO trip, Integer fromStopSeq, Integer toStopSeq) {
        int size = (trip.getStops() == null || trip.getStops().isEmpty()) ? 2 : trip.getStops().size();
        int last = size - 1;
        int from = fromStopSeq == null ? 0 : Math.min(Math.max(fromStopSeq, 0), last);
        int to = toStopSeq == null ? last : Math.min(Math.max(toStopSeq, from), last);
        if (to <= from) {
            to = from + 1;
        }
        return new int[]{from, to};
    }

    /**
     * Redis 并发闸门（区间感知）：
     * 有区间数据 → per-segment 多 key Lua「先检查后扣减」，任一不足整体失败；
     * 无区间数据 → 旧全程标量 key。
     * 返回值：区间版 1=成功 / -1=余座不足 / -2=未初始化；全程版返回扣减后余量或 -1/-2。
     */
    private long deductSeatRedis(Long tripId, int fromSeq, int toSeq, int num) {
        List<java.util.Map<String, Integer>> segments = tripSegmentMapper.selectCoveredSegments(tripId, fromSeq, toSeq);
        if (segments != null && !segments.isEmpty()) {
            List<String> segKeys = segmentRedisKeys(tripId, segments);
            ensureSegmentKeys(tripId, segments, segKeys);
            Long r = redisTemplate.execute(new DefaultRedisScript<>(DEDUCT_SEGMENTS_LUA, Long.class),
                    segKeys, String.valueOf(num));
            if (r != null && r == -2) {
                // key 被清（如 Redis 重启）：按 DB 初始化后重试一次
                initSegmentKeys(tripId, segments);
                r = redisTemplate.execute(new DefaultRedisScript<>(DEDUCT_SEGMENTS_LUA, Long.class),
                        segKeys, String.valueOf(num));
            }
            return r == null ? -2 : r;
        }
        return deductSeat(tripId, num);
    }

    /**
     * DB 条件扣减兜底（防超卖）：
     * 有区间数据 → 区间覆盖条件扣减（影响行数须等于覆盖区间数）+ 全程标量同步；
     * 无区间数据 → 全程标量条件扣减。
     * 在事务内调用：失败抛出后 DB 扣减随事务回滚。
     */
    private boolean deductSeatDb(Long tripId, int fromSeq, int toSeq, int num) {
        List<java.util.Map<String, Integer>> segments = tripSegmentMapper.selectCoveredSegments(tripId, fromSeq, toSeq);
        if (segments != null && !segments.isEmpty()) {
            int affected = tripSegmentMapper.deductSegment(tripId, fromSeq, toSeq, num);
            if (affected != segments.size()) {
                return false;
            }
            // 全程标量冗余同步（搜索/详情快速判断用；即便同步失败也不影响区间正确性）
            tripSeatMapper.deductSeat(tripId, num);
            return true;
        }
        return tripSeatMapper.deductSeat(tripId, num) > 0;
    }

    /**
     * 仅回补 Redis（下单失败场景专用）：DB 扣减与建单同事务、随异常回滚，
     * 这里只还回本请求占住的 Redis 余座，避免"DB 没扣却手动回补"造成超发。
     */
    private void restoreSegmentRedisOnly(Long tripId, int fromSeq, int toSeq, int num) {
        List<java.util.Map<String, Integer>> segments = tripSegmentMapper.selectCoveredSegments(tripId, fromSeq, toSeq);
        if (segments != null && !segments.isEmpty()) {
            List<String> segKeys = segmentRedisKeys(tripId, segments);
            ensureSegmentKeys(tripId, segments, segKeys);
            redisTemplate.execute(new DefaultRedisScript<>(RESTORE_SEGMENTS_LUA, Long.class),
                    segKeys, String.valueOf(num));
            return;
        }
        restoreSeat(tripId, num);
    }

    /** 区间/全程回补库存（正常提交路径：取消/关单，DB 与 Redis 双回补） */
    private void restoreSegmentOrSeat(Long tripId, int fromSeq, int toSeq, int num) {
        List<java.util.Map<String, Integer>> segments = tripSegmentMapper.selectCoveredSegments(tripId, fromSeq, toSeq);
        if (segments != null && !segments.isEmpty()) {
            List<String> segKeys = segmentRedisKeys(tripId, segments);
            ensureSegmentKeys(tripId, segments, segKeys);
            // Redis 区间回补（区间订单下单时只扣了区间 key，不碰全程 key，故全程 Redis key 不回补）
            redisTemplate.execute(new DefaultRedisScript<>(RESTORE_SEGMENTS_LUA, Long.class),
                    segKeys, String.valueOf(num));
            // DB 区间回补
            tripSegmentMapper.restoreSegment(tripId, fromSeq, toSeq, num);
        } else {
            // 无区间行程：Redis 全程 key 回补
            restoreSeat(tripId, num);
        }
        // DB 全程标量同步回补（下单时 deductSeatDb 同步扣过）
        tripSeatMapper.restoreSeat(tripId, num);
    }

    /** 构造区间 Redis key 列表（与 trip 服务发布时的初始化口径一致） */
    private List<String> segmentRedisKeys(Long tripId, List<java.util.Map<String, Integer>> segments) {
        return segments.stream()
                .map(s -> SEG_KEY_PREFIX + tripId + ":" + s.get("from_seq") + "-" + s.get("to_seq"))
                .collect(Collectors.toList());
    }

    /** 区间 key 自愈：存在任一缺失则全部按 DB 当前余座重置（保证 Lua 检查可执行） */
    private void ensureSegmentKeys(Long tripId, List<java.util.Map<String, Integer>> segments, List<String> segKeys) {
        try {
            for (String key : segKeys) {
                if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                    initSegmentKeys(tripId, segments);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("区间 key 自愈检查失败 tripId={}: {}", tripId, e.getMessage());
        }
    }

    /** 按 DB 区间余座初始化全部区间 key（幂等：SET 覆盖写） */
    private void initSegmentKeys(Long tripId, List<java.util.Map<String, Integer>> segments) {
        try {
            for (java.util.Map<String, Integer> s : segments) {
                Integer remain = tripSegmentMapper.minRemainSeats(tripId, s.get("from_seq"), s.get("to_seq"));
                if (remain != null) {
                    redisTemplate.opsForValue().set(
                            SEG_KEY_PREFIX + tripId + ":" + s.get("from_seq") + "-" + s.get("to_seq"),
                            String.valueOf(remain));
                }
            }
        } catch (Exception e) {
            log.warn("区间 key 初始化失败 tripId={}: {}", tripId, e.getMessage());
        }
    }

    /** 订单区间里程（公里）：有站点与里程单价时按区间累计，否则回退整段里程 */
    private BigDecimal calcSegmentDistance(TripDTO trip, int fromSeq, int toSeq) {
        if (trip.getTotalDistanceKm() != null && trip.getStops() != null) {
            // 按区间占比估算：区间数 / 总区间数 × 整段里程
            int totalSegments = trip.getStops().size() - 1;
            int orderSegments = toSeq - fromSeq;
            if (totalSegments > 0) {
                return trip.getTotalDistanceKm()
                        .multiply(BigDecimal.valueOf(orderSegments))
                        .divide(BigDecimal.valueOf(totalSegments), 2, java.math.RoundingMode.HALF_UP);
            }
        }
        return trip.getTotalDistanceKm() == null ? BigDecimal.ZERO : trip.getTotalDistanceKm();
    }

    /** 订单计价：优先区间里程 × 每公里单价，否则回退旧"单价 × 座位数" */
    private BigDecimal calcOrderPrice(TripDTO trip, int fromSeq, int toSeq, int seatCount) {
        if (trip.getPricePerKm() != null) {
            BigDecimal distance = calcSegmentDistance(trip, fromSeq, toSeq);
            return trip.getPricePerKm().multiply(distance)
                    .multiply(BigDecimal.valueOf(seatCount))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return trip.getPrice().multiply(BigDecimal.valueOf(seatCount));
    }

    /** 订单实体取上车站点序号（可能为 null，兼容旧数据） */
    private int safeFromSeq(TripOrder order) {
        return order.getFromStopSeq() == null ? 0 : order.getFromStopSeq();
    }

    /** 订单实体取下车站点序号（可能为 null，兼容旧数据，兜底至少比 from 大 1） */
    private int safeToSeq(TripOrder order) {
        int from = safeFromSeq(order);
        return order.getToStopSeq() == null || order.getToStopSeq() <= from
                ? from + 1 : order.getToStopSeq();
    }

    /** 退款单号生成 */
    private String generateRefundNo() {
        return "R" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }

    /**
     * 发送订单事件消息（通知服务消费生成站内信，阶段2）。
     * 事件类型：ORDER_PAID / ORDER_CANCELLED / ORDER_EXPIRED / REFUND_SUCCESS。
     * 发送失败不阻塞主流程（通知尽力而为）。
     */
    private void sendOrderEvent(String type, Long userId, String orderNo, BigDecimal amount) {
        try {
            Map<String, Object> event = new java.util.HashMap<>();
            event.put("type", type);
            event.put("userId", userId);
            event.put("orderNo", orderNo);
            if (amount != null) {
                event.put("amount", amount.toPlainString());
            }
            String payload = objectMapper.writeValueAsString(event);
            rocketMQTemplate.convertAndSend("GO_SHARE_ORDER_EVENT", payload);
            log.info("订单事件已发送 type={} orderNo={}", type, orderNo);
        } catch (Exception e) {
            log.warn("订单事件发送失败 type={} orderNo={}: {}", type, orderNo, e.getMessage());
        }
    }

    /**
     * 调用支付服务执行退款（演示渠道 MOCK 立即成功）。
     * 返回是否退款成功；失败时退款单保持 REFUNDING 待重试。
     */
    private boolean callPaymentRefund(String paymentNo, BigDecimal amount) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of(
                    "paymentNo", paymentNo == null ? "" : paymentNo,
                    "amount", amount), headers);
            Result<?> result = restTemplate.exchange(
                            paymentServiceUrl + "/api/payment/refund",
                            HttpMethod.POST, entity,
                            new ParameterizedTypeReference<Result<?>>() {})
                    .getBody();
            return result != null && result.getCode() == 200;
        } catch (Exception e) {
            log.warn("调用支付服务退款失败 paymentNo={}: {}", paymentNo, e.getMessage());
            return false;
        }
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

    /** 幂等快照直接存「最终要返回给前端的 VO」，回放时与原响应完全一致（含行程摘要与创建时间） */
    private void saveIdempotency(String key, String bizType, OrderVO vo) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotentKey(key);
        record.setBizType(bizType);
        record.setResponseJson(toJson(vo));
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
        vo.setFromStopSeq(order.getFromStopSeq());
        vo.setToStopSeq(order.getToStopSeq());
        vo.setDistanceKm(order.getDistanceKm());
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

    // ---------- 履约闭环（阶段1新增） ----------

    /** 车主视角：某行程的乘客订单名单（仅该行程的车主可见） */
    @Override
    public List<OrderVO> listByTrip(Long driverId, Long tripId) {
        TripDTO trip = fetchTrip(tripId);
        if (trip == null || !trip.getId().equals(tripId)) {
            throw new BusinessException(404, "行程不存在");
        }
        // 仅车主本人可看乘客名单
        if (driverId != null && !driverId.equals(trip.getDriverId())) {
            throw new BusinessException(403, "仅该行程车主可查看乘客名单");
        }
        return orderMapper.selectList(new LambdaQueryWrapper<TripOrder>()
                        .eq(TripOrder::getTripId, tripId)
                        .in(TripOrder::getStatus, OrderStatus.PAID, OrderStatus.BOARDED, OrderStatus.COMPLETED, OrderStatus.REVIEWED)
                        .orderByAsc(TripOrder::getCreatedAt))
                .stream().map(this::toVOWithTrip).collect(Collectors.toList());
    }

    /** 车主确认乘客上车：PAID → BOARDED（CAS） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO board(Long driverId, Long tripId, String orderNo) {
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        // 校验归属：该订单的行程属于该车主
        TripDTO trip = fetchTrip(tripId);
        if (trip == null || !driverId.equals(trip.getDriverId())) {
            throw new BusinessException(403, "仅该行程车主可确认上车");
        }
        if (!OrderStatus.PAID.equals(order.getStatus())) {
            throw new BusinessException(400, "仅已支付订单可确认上车");
        }
        order.setStatus(OrderStatus.BOARDED);
        int rows = orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getPassengerId, order.getPassengerId())
                .eq(TripOrder::getStatus, OrderStatus.PAID));
        if (rows == 0) {
            throw new BusinessException(409, "订单状态已变化");
        }
        return toVO(order);
    }

    /** 车主确认到达：BOARDED → COMPLETED（CAS） */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO complete(Long driverId, Long tripId, String orderNo) {
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        TripDTO trip = fetchTrip(tripId);
        if (trip == null || !driverId.equals(trip.getDriverId())) {
            throw new BusinessException(403, "仅该行程车主可确认到达");
        }
        if (!OrderStatus.BOARDED.equals(order.getStatus())) {
            throw new BusinessException(400, "仅已上车订单可确认到达");
        }
        order.setStatus(OrderStatus.COMPLETED);
        int rows = orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getPassengerId, order.getPassengerId())
                .eq(TripOrder::getStatus, OrderStatus.BOARDED));
        if (rows == 0) {
            throw new BusinessException(409, "订单状态已变化");
        }
        return toVO(order);
    }

    /** 乘客评价：COMPLETED → REVIEWED，落评价并派生车主信任分 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO review(Long passengerId, String orderNo, int rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new BusinessException(400, "评分须在 1~5 之间");
        }
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (!passengerId.equals(order.getPassengerId())) {
            throw new BusinessException(403, "仅本人可评价");
        }
        if (!OrderStatus.COMPLETED.equals(order.getStatus())) {
            throw new BusinessException(400, "仅已完成订单可评价");
        }
        // 落评价（uk_order_reviewer 唯一，重复评价幂等拦截）
        OrderReview review = new OrderReview();
        review.setOrderNo(orderNo);
        review.setReviewerId(passengerId);
        review.setRevieweeId(fetchTrip(order.getTripId()).getDriverId());
        review.setTripId(order.getTripId());
        review.setRating(rating);
        review.setComment(comment);
        try {
            orderReviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(400, "该订单已评价");
        }
        // 订单 COMPLETED → REVIEWED（CAS）
        order.setStatus(OrderStatus.REVIEWED);
        int rows = orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                .eq(TripOrder::getId, order.getId())
                .eq(TripOrder::getPassengerId, order.getPassengerId())
                .eq(TripOrder::getStatus, OrderStatus.COMPLETED));
        if (rows == 0) {
            throw new BusinessException(409, "订单状态已变化");
        }
        return toVO(order);
    }

    /**
     * 线路价格统计（F3 价格参谋：同线路近 N 天均价/最低/中位数）。
     *
     * <p>纯统计、确定性计算，零幻觉风险。数据全部来自真实订单表。
     * 近 N 天无订单时返回空统计（orderCount=0），由调用方决定如何措辞。
     */
    @Override
    public com.goshare.order.dto.PriceStatsVO priceStats(String fromCity, String toCity, int days) {
        if (days <= 0 || days > 365) {
            days = 30;
        }
        com.goshare.order.dto.PriceStatsVO vo = new com.goshare.order.dto.PriceStatsVO();
        vo.setFromCity(fromCity);
        vo.setToCity(toCity);
        vo.setDays(days);

        List<Long> tripIds = priceStatsMapper.selectTripIds(fromCity, toCity);
        if (tripIds == null || tripIds.isEmpty()) {
            vo.setOrderCount(0);
            return vo;
        }
        List<BigDecimal> prices = priceStatsMapper.selectPayPrices(tripIds, days);
        if (prices == null || prices.isEmpty()) {
            vo.setOrderCount(0);
            return vo;
        }
        vo.setOrderCount(prices.size());
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min = null;
        for (BigDecimal p : prices) {
            if (p == null) {
                continue;
            }
            sum = sum.add(p);
            if (min == null || p.compareTo(min) < 0) {
                min = p;
            }
        }
        // 中位数：排序后取中间值
        List<BigDecimal> sorted = prices.stream()
                .filter(p -> p != null)
                .sorted()
                .collect(Collectors.toList());
        BigDecimal median;
        int size = sorted.size();
        if (size % 2 == 1) {
            median = sorted.get(size / 2);
        } else {
            median = sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
        }
        int validCount = sorted.size();
        vo.setAvgPrice(validCount == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(validCount), 2, java.math.RoundingMode.HALF_UP));
        vo.setMinPrice(min == null ? BigDecimal.ZERO : min);
        vo.setMedianPrice(median);
        return vo;
    }

    /**
     * 行程账单（F5）：按乘客月度聚合。数据 100% 来自真实订单表：
     * 次数/总里程/总花费为聚合事实；「网约车参考价」按写死的 2.3 元/公里估算基准
     * （确定性口径，文案注明估算），不做无数据支撑的编造对比。
     * passenger_id 是分片键，查询精准路由单片。
     */
    @Override
    public com.goshare.order.dto.TripBillVO tripBill(Long passengerId, String month) {
        // 1. 归一化月份（缺省当月），计算 [start, end)
        java.time.YearMonth ym;
        try {
            ym = (month == null || month.isBlank())
                    ? java.time.YearMonth.now() : java.time.YearMonth.parse(month);
        } catch (Exception e) {
            throw new BusinessException(400, "月份格式应为 yyyy-MM");
        }
        String start = ym.atDay(1).atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String end = ym.plusMonths(1).atDay(1).atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        com.goshare.order.dto.TripBillVO vo = new com.goshare.order.dto.TripBillVO();
        vo.setMonth(ym.toString());

        // 2. 聚合事实
        Map<String, Object> agg = orderMapper.aggregateBill(passengerId, start, end);
        int orderCount = agg == null || agg.get("orderCount") == null ? 0 : ((Number) agg.get("orderCount")).intValue();
        BigDecimal totalDistance = agg == null || agg.get("totalDistanceKm") == null
                ? BigDecimal.ZERO : new BigDecimal(String.valueOf(agg.get("totalDistanceKm")));
        BigDecimal totalPay = agg == null || agg.get("totalPay") == null
                ? BigDecimal.ZERO : new BigDecimal(String.valueOf(agg.get("totalPay")));
        vo.setOrderCount(orderCount);
        vo.setTotalDistanceKm(totalDistance.setScale(1, java.math.RoundingMode.HALF_UP));
        vo.setTotalPay(totalPay.setScale(2, java.math.RoundingMode.HALF_UP));

        // 3. 网约车参考价（确定性估算基准：2.3 元/公里）与省钱估算
        BigDecimal taxiBenchmark = totalDistance.multiply(new BigDecimal("2.3")).setScale(2, java.math.RoundingMode.HALF_UP);
        vo.setTaxiBenchmark(taxiBenchmark);
        vo.setSavedEstimate(taxiBenchmark.subtract(totalPay));

        // 4. 最常线路 Top3
        List<com.goshare.order.dto.TripBillVO.TopRoute> routes = new java.util.ArrayList<>();
        List<Map<String, Object>> groups = orderMapper.groupByTrip(passengerId, start, end);
        if (groups != null) {
            for (Map<String, Object> g : groups) {
                Long tripId = ((Number) g.get("trip_id")).longValue();
                int cnt = ((Number) g.get("cnt")).intValue();
                Map<String, Object> trip = orderMapper.selectTripCities(tripId);
                com.goshare.order.dto.TripBillVO.TopRoute r = new com.goshare.order.dto.TripBillVO.TopRoute();
                if (trip != null) {
                    r.setFromCity(String.valueOf(trip.get("from_city")));
                    r.setToCity(String.valueOf(trip.get("to_city")));
                } else {
                    r.setFromCity("未知");
                    r.setToCity("未知");
                }
                r.setCount(cnt);
                routes.add(r);
            }
        }
        vo.setTopRoutes(routes);
        log.info("[trip-bill] 账单已生成 passengerId={} month={} count={}", passengerId, ym, orderCount);
        return vo;
    }

    /**
     * 改签（F4）：PAID 订单改签到同线路新班次。
     *
     * <p>规则（全部确定性，AI 只负责话术）：
     * <ol>
     *   <li>订单归属本人 + 状态 PAID（CAS WHERE status='PAID' 防与取消/退款并发）；</li>
     *   <li>原行程未发车；新行程存在、未出发、可预订、同线路（fromCity/toCity 一致）；</li>
     *   <li>座位沿用原订单上下车区间（新行程站点数不同时 clamp 到合法范围）；</li>
     *   <li>新价 ≤ 原实付：改签免费，差额走部分退款流水即时退回；
     *       新价 &gt; 原实付：拒绝（演示环境不支持补差价，提示取消后重新下单）；</li>
     *   <li>库存：新区间先 Redis 闸门扣减 + DB 兜底，原区间 DB 回补（Redis 闸门不动——原订单下单扣的是旧区间 key，需同步回补）。</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVO reschedule(Long passengerId, String orderNo, Long newTripId, Integer newFromStopSeq, Integer newToStopSeq) {
        // 1. 订单归属 + 状态
        TripOrder order = orderMapper.selectOne(new LambdaQueryWrapper<TripOrder>()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getPassengerId, passengerId));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        if (!OrderStatus.PAID.equals(order.getStatus())) {
            throw new BusinessException(400, "仅已支付订单可改签");
        }

        // 2. 原行程（未发车）+ 新行程（存在/可预订/同线路/未出发）
        TripDTO oldTrip = fetchTrip(order.getTripId());
        if (oldTrip == null) {
            throw new BusinessException(500, "行程服务暂不可用");
        }
        if (!oldTrip.getDepartureTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "原行程已发车，无法改签");
        }
        TripDTO newTrip = fetchTrip(newTripId);
        if (newTrip == null) {
            throw new BusinessException(404, "目标行程不存在");
        }
        if (newTrip.getStatus() != 0 && newTrip.getStatus() != 1) {
            throw new BusinessException(400, "目标行程不可预订");
        }
        if (!newTrip.getDepartureTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, "目标行程已出发");
        }
        if (!oldTrip.getFromCity().equals(newTrip.getFromCity())
                || !oldTrip.getToCity().equals(newTrip.getToCity())) {
            throw new BusinessException(400, "仅支持同线路改签（"
                    + oldTrip.getFromCity() + "→" + oldTrip.getToCity() + "）");
        }

        // 3. 区间沿用原订单序号（新行程站点数不同时 clamp）
        int[] seg = resolveSegment(newTrip, newFromStopSeq != null ? newFromStopSeq : safeFromSeq(order),
                newToStopSeq != null ? newToStopSeq : safeToSeq(order));
        int fromSeq = seg[0];
        int toSeq = seg[1];

        // 4. 新价（区间计价）与差额判定
        BigDecimal newPrice = calcOrderPrice(newTrip, fromSeq, toSeq, order.getSeatCount());
        if (newPrice.compareTo(order.getPayPrice()) > 0) {
            throw new BusinessException(400, "新班次价格（" + newPrice + " 元）高于原实付（"
                    + order.getPayPrice() + " 元），暂不支持补差价改签，可取消后重新下单");
        }

        // 5. 新区间库存：Redis 闸门 + DB 兜底（事务内失败会回滚 DB，Redis 由 catch 回补）
        long remainAfter = deductSeatRedis(newTripId, fromSeq, toSeq, order.getSeatCount());
        if (remainAfter == -1) {
            throw new BusinessException(400, "目标班次余座不足");
        }
        if (remainAfter == -2) {
            throw new BusinessException(400, "目标班次库存未初始化");
        }
        // 保存旧行程 ID 与原区间（第 6 步会更新 order.tripId，回补必须用旧值）
        final Long oldTripId = order.getTripId();
        final int oldFromSeq = safeFromSeq(order);
        final int oldToSeq = safeToSeq(order);
        try {
            if (!deductSeatDb(newTripId, fromSeq, toSeq, order.getSeatCount())) {
                throw new BusinessException(400, "目标班次余座不足");
            }

            // 6. CAS 更新订单（tripId/区间/金额/行程摘要）
            BigDecimal diff = order.getPayPrice().subtract(newPrice);
            order.setTripId(newTripId);
            order.setFromStopSeq(fromSeq);
            order.setToStopSeq(toSeq);
            order.setDistanceKm(calcSegmentDistance(newTrip, fromSeq, toSeq));
            order.setTotalPrice(newPrice);
            order.setPayPrice(newPrice);
            int rows = orderMapper.update(order, new LambdaUpdateWrapper<TripOrder>()
                    .eq(TripOrder::getId, order.getId())
                    .eq(TripOrder::getPassengerId, order.getPassengerId())
                    .eq(TripOrder::getStatus, OrderStatus.PAID));
            if (rows == 0) {
                throw new BusinessException(409, "订单状态已变化（可能已取消/退款），改签被拒绝");
            }

            // 7. 原区间库存回补（DB 区间 + DB 全程标量 + 原区间 Redis key，全部用保存的旧值）
            restoreSegmentOrSeat(oldTripId, oldFromSeq, oldToSeq, order.getSeatCount());

            // 8. 差额部分退款（新价 < 原实付时；改签免费不收违约金）
            String diffRefundNo = null;
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                diffRefundNo = generateRefundNo();
                RefundOrder refund = new RefundOrder();
                refund.setRefundNo(diffRefundNo);
                refund.setOrderNo(orderNo);
                refund.setPassengerId(passengerId);
                refund.setPaymentNo(order.getPaymentNo());
                refund.setRefundAmount(diff);
                refund.setFeeAmount(BigDecimal.ZERO);
                refund.setReason("改签差额退款");
                refund.setStatus(RefundOrder.STATUS_SUCCESS);
                refund.setRefundedAt(LocalDateTime.now());
                refundOrderMapper.insert(refund);
                RefundTransaction tx = new RefundTransaction();
                tx.setRefundNo(diffRefundNo);
                tx.setOrderNo(orderNo);
                tx.setPaymentNo(order.getPaymentNo());
                tx.setChannel("MOCK");
                tx.setAmount(diff);
                tx.setStatus("SUCCESS");
                tx.setChannelRefNo("MOCK-PARTIAL-" + System.currentTimeMillis());
                refundTransactionMapper.insert(tx);
                log.info("改签差额退款成功 orderNo={} diff={}", orderNo, diff);
            }
            OrderVO vo = toVOWithTrip(order);
            vo.setRefundAmount(diff.compareTo(BigDecimal.ZERO) > 0 ? diff : null);
            vo.setRefundNo(diffRefundNo);
            sendOrderEvent("ORDER_RESCHEDULED", passengerId, orderNo, null);
            log.info("改签成功 orderNo={} newTripId={} newPrice={} diff={}", orderNo, newTripId, newPrice, diff);
            return vo;
        } catch (Exception e) {
            // DB 事务回滚，新区间的 Redis 扣减需回补
            restoreSegmentRedisOnly(newTripId, fromSeq, toSeq, order.getSeatCount());
            throw e;
        }
    }
}
