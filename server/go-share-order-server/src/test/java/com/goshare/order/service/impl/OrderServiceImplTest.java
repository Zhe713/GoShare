package com.goshare.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.goshare.common.exception.BusinessException;
import com.goshare.common.result.Result;
import com.goshare.order.dto.OrderCreateRequest;
import com.goshare.order.dto.OrderVO;
import com.goshare.order.dto.TripDTO;
import com.goshare.order.entity.IdempotencyRecord;
import com.goshare.order.entity.TripOrder;
import com.goshare.order.mapper.IdempotencyRecordMapper;
import com.goshare.order.mapper.OrderReviewMapper;
import com.goshare.order.mapper.PriceStatsMapper;
import com.goshare.order.mapper.RefundOrderMapper;
import com.goshare.order.mapper.RefundTransactionMapper;
import com.goshare.order.mapper.TripOrderMapper;
import com.goshare.order.mapper.TripSeatMapper;
import com.goshare.order.mapper.TripSegmentMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订单服务核心链路：下单幂等/余座校验/取消回补递补/支付回调幂等/超时关闭/候补登记
 */
class OrderServiceImplTest {

    static {
        // LambdaUpdateWrapper 的 set(TripOrder::getStatus, ...) 需要实体元数据缓存；
        // 纯单测没有 MyBatis 启动流程，手动初始化一次（订单 + 评价）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TripOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                com.goshare.order.entity.OrderReview.class);
    }

    private TripOrderMapper orderMapper;
    private IdempotencyRecordMapper idempotencyMapper;
    private TripSeatMapper tripSeatMapper;
    private TripSegmentMapper tripSegmentMapper;
    private RefundOrderMapper refundOrderMapper;
    private RefundTransactionMapper refundTransactionMapper;
    private OrderReviewMapper orderReviewMapper;
    private PriceStatsMapper priceStatsMapper;
    private StringRedisTemplate redisTemplate;
    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private RocketMQTemplate rocketMQTemplate;
    private OrderServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderMapper = mock(TripOrderMapper.class);
        idempotencyMapper = mock(IdempotencyRecordMapper.class);
        tripSeatMapper = mock(TripSeatMapper.class);
        tripSegmentMapper = mock(TripSegmentMapper.class);
        refundOrderMapper = mock(RefundOrderMapper.class);
        refundTransactionMapper = mock(RefundTransactionMapper.class);
        orderReviewMapper = mock(OrderReviewMapper.class);
        priceStatsMapper = mock(PriceStatsMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        restTemplate = mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        // 生产环境注入的是 Spring Boot 配置好的 ObjectMapper（已注册 JavaTimeModule），
        // 单测里必须手动注册，否则幂等快照里的 LocalDateTime 无法序列化
        objectMapper.registerModule(new JavaTimeModule());
        rocketMQTemplate = mock(RocketMQTemplate.class);
        service = new OrderServiceImpl(orderMapper, idempotencyMapper, tripSeatMapper,
                tripSegmentMapper, refundOrderMapper, refundTransactionMapper, orderReviewMapper,
                priceStatsMapper,
                redisTemplate, restTemplate, objectMapper, rocketMQTemplate);
        ReflectionTestUtils.setField(service, "tripServiceUrl", "http://trip:8002");
        ReflectionTestUtils.setField(service, "timeoutMinutes", 10L);
        ReflectionTestUtils.setField(service, "scheduledTimeoutMinutes", 12L);
        ReflectionTestUtils.setField(service, "marketingServiceUrl", "http://marketing:8004");
        when(redisTemplate.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        // 默认：无区间数据（selectCoveredSegments 返回空），走全程标量扣减，兼容既有测试
        when(tripSegmentMapper.selectCoveredSegments(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        when(tripSeatMapper.deductSeat(anyLong(), anyInt())).thenReturn(1);
    }

    private TripDTO futureTrip() {
        TripDTO trip = new TripDTO();
        trip.setId(5L);
        trip.setDepartureTime(LocalDateTime.now().plusDays(1));
        trip.setPrice(new BigDecimal("60"));
        trip.setRemainSeats(4);
        trip.setStatus(0);
        return trip;
    }

    private OrderCreateRequest createReq() {
        OrderCreateRequest req = new OrderCreateRequest();
        req.setTripId(5L);
        req.setSeatCount(1);
        req.setIdempotentKey("idem-001");
        return req;
    }

    private void mockFetchTrip(TripDTO trip) {
        when(restTemplate.exchange(contains("/api/trip/"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(trip)));
    }

    @Test
    void createReturnsFirstResultWhenIdempotent() throws Exception {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotentKey("CREATE_ORDER:idem-001");
        OrderVO first = new OrderVO();
        first.setOrderNo("202610301500000001");
        first.setStatus("CREATED");
        record.setResponseJson(objectMapper.writeValueAsString(first));
        when(idempotencyMapper.selectOne(any(Wrapper.class))).thenReturn(record);

        OrderVO vo = service.create(2L, createReq());

        assertEquals("202610301500000001", vo.getOrderNo());
        verify(orderMapper, never()).insert(any(TripOrder.class));
    }

    @Test
    void createRejectsTripNotFound() {
        mockFetchTrip(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(2L, createReq()));
        assertEquals(404, ex.getCode());
    }

    @Test
    void createRejectsTripDeparted() {
        TripDTO trip = futureTrip();
        trip.setDepartureTime(LocalDateTime.now().minusMinutes(5));
        mockFetchTrip(trip);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(2L, createReq()));
        assertEquals(400, ex.getCode());
    }

    @Test
    void createRejectsInsufficientSeats() {
        mockFetchTrip(futureTrip());
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(-1L); // Lua 扣减返回 -1
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(2L, createReq()));
        assertEquals(400, ex.getCode());
    }

    @Test
    void createRejectsUninitializedSeats() {
        mockFetchTrip(futureTrip());
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(-2L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(2L, createReq()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("未初始化"));
    }

    @Test
    void createSuccessPersistsOrderAndIdempotency() {
        mockFetchTrip(futureTrip());
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(3L); // 扣减后余量
        when(tripSeatMapper.deductSeat(5L, 1)).thenReturn(1); // DB 条件扣减成功
        when(orderMapper.insert(any(TripOrder.class))).thenAnswer(inv -> {
            TripOrder o = inv.getArgument(0);
            o.setId(1L);
            return 1;
        });

        OrderVO vo = service.create(2L, createReq());

        assertEquals("CREATED", vo.getStatus());
        assertEquals(new BigDecimal("60"), vo.getPayPrice());
        // 订单号生成 + 幂等记录落库
        ArgumentCaptor<TripOrder> captor = ArgumentCaptor.forClass(TripOrder.class);
        verify(orderMapper).insert(captor.capture());
        assertEquals("CREATED", captor.getValue().getStatus());
        assertEquals(2L, captor.getValue().getPassengerId());
        verify(idempotencyMapper).insert(any(IdempotencyRecord.class));
    }

    @Test
    void createRestoresSeatWhenDbDeductFails() {
        mockFetchTrip(futureTrip());
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(3L);
        when(tripSeatMapper.deductSeat(5L, 1)).thenReturn(0); // DB 条件扣减失败
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(2L, createReq()));
        assertEquals(400, ex.getCode());
        // Redis 回补（防扣了票却没建单）
        verify(redisTemplate, atLeastOnce()).execute(any(), anyList(), any(Object[].class));
    }

    @Test
    void cancelPaidOrderGoesToRefund() {
        TripOrder order = new TripOrder();
        order.setId(1L);
        order.setOrderNo("20261030151932907564");
        order.setPassengerId(2L);
        order.setTripId(5L);
        order.setSeatCount(1);
        order.setTotalPrice(new BigDecimal("60"));
        order.setPayPrice(new BigDecimal("60"));
        order.setStatus("PAID");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        // 已支付订单取消 → 走退款闭环：需要行程服务返回发车时间（用于算违约金）
        TripDTO trip = futureTrip();
        mockFetchTrip(trip);
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(tripSeatMapper.restoreSeat(anyLong(), anyInt())).thenReturn(1);
        when(redisTemplate.opsForZSet().range(anyString(), eq(0L), eq(0L))).thenReturn(Set.of());
        // 退款：支付服务退款成功
        when(restTemplate.exchange(contains("/api/payment/refund"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(true)));

        OrderVO vo = service.cancel(2L, "20261030151932907564");

        // 退款成功 → 订单 REFUNDED
        assertEquals("REFUNDED", vo.getStatus());
        // 落退款单 + 退款流水
        verify(refundOrderMapper).insert(any(com.goshare.order.entity.RefundOrder.class));
        verify(refundTransactionMapper).insert(any(com.goshare.order.entity.RefundTransaction.class));
    }

    @Test
    void cancelSuccessRestoresSeatAndPromotesWaitlist() {
        TripOrder order = new TripOrder();
        order.setId(1L);
        order.setOrderNo("20261030151932907564");
        order.setPassengerId(2L);
        order.setTripId(5L);
        order.setSeatCount(2);
        order.setStatus("CREATED");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        // CAS：状态更新必须影响 1 行才算成功（并发冲突时返回 0）
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(tripSeatMapper.restoreSeat(5L, 2)).thenReturn(1);
        when(redisTemplate.opsForZSet().range(anyString(), eq(0L), eq(0L))).thenReturn(Set.of());

        OrderVO vo = service.cancel(2L, "20261030151932907564");

        assertEquals("CANCELLED", vo.getStatus());
        verify(tripSeatMapper).restoreSeat(5L, 2); // DB 回补
        verify(redisTemplate, atLeastOnce()).execute(any(), anyList(), any(Object[].class)); // Redis 回补
    }

    @Test
    void payCallbackIdempotentByPaymentNo() {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotentKey("PAY_CALLBACK:P-001");
        when(idempotencyMapper.selectOne(any(Wrapper.class))).thenReturn(record);
        // 幂等记录存在：直接返回（不查订单）
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        try {
            service.handlePayCallback("20261030151932907564", "P-001");
            fail("期望解析幂等 JSON 抛出业务异常（空响应）");
        } catch (BusinessException ignored) {
            // 幂等记录存在但 JSON 空 → 解析失败（说明未进入订单主流程）
        }
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    void payCallbackRejectsPaidOrderWithDifferentPaymentNo() {
        TripOrder order = new TripOrder();
        order.setId(1L);
        order.setOrderNo("20261030151932907564");
        order.setPassengerId(2L);
        order.setStatus("PAID");
        order.setPaymentNo("P-OTHER");
        when(idempotencyMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handlePayCallback("20261030151932907564", "P-001"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void payCallbackMarksPaid() {
        TripOrder order = new TripOrder();
        order.setId(1L);
        order.setOrderNo("20261030151932907564");
        order.setPassengerId(2L);
        order.setTripId(5L);
        order.setStatus("CREATED");
        order.setPayPrice(new BigDecimal("90.00"));
        when(idempotencyMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(any(), any())).thenReturn(1);
        // 回调现在会回查支付流水：必须是 SUCCESS、同订单、金额一致
        Result<Map<String, Object>> paymentResp = Result.success(Map.of(
                "status", "SUCCESS",
                "orderNo", "20261030151932907564",
                "amount", "90.00"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(new ResponseEntity<>(paymentResp, HttpStatus.OK));

        OrderVO vo = service.handlePayCallback("20261030151932907564", "P-001");

        assertEquals("PAID", vo.getStatus());
        assertEquals("P-001", vo.getPaymentNo());
        verify(idempotencyMapper).insert(any(IdempotencyRecord.class));
    }

    @Test
    void closeExpiredSkipsNonCreatedOrder() {
        TripOrder paid = new TripOrder();
        paid.setId(1L);
        paid.setOrderNo("20261030151932907564");
        paid.setPassengerId(2L);
        paid.setStatus("PAID");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(paid);

        service.closeExpiredOrderByNo("20261030151932907564");

        // 非 CREATED 不更新、不回补（状态机防重复）
        verify(orderMapper, never()).update(any(), any());
        verify(tripSeatMapper, never()).restoreSeat(any(), anyInt());
    }

    @Test
    void releaseExpiredOrdersClosesExpiredOnes() {
        TripOrder expired = new TripOrder();
        expired.setId(1L);
        expired.setOrderNo("20261030151932907564");
        expired.setPassengerId(2L);
        expired.setTripId(5L);
        expired.setSeatCount(1);
        expired.setStatus("CREATED");
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(expired));
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(tripSeatMapper.restoreSeat(5L, 1)).thenReturn(1);
        when(redisTemplate.opsForZSet().range(anyString(), eq(0L), eq(0L))).thenReturn(Set.of());

        int n = service.releaseExpiredOrders();

        assertEquals(1, n);
        verify(orderMapper).update(argThat(o -> "EXPIRED".equals(o.getStatus())), any());
    }

    @Test
    void joinWaitlistRejectsWhenSeatsAvailable() {
        TripDTO trip = futureTrip();
        trip.setRemainSeats(2);
        mockFetchTrip(trip);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.joinWaitlist(3L, 5L));
        assertEquals(400, ex.getCode());
    }

    @Test
    void joinWaitlistQueuesWhenFull() {
        TripDTO trip = futureTrip();
        trip.setRemainSeats(0);
        mockFetchTrip(trip);
        service.joinWaitlist(3L, 5L);
        verify(redisTemplate.opsForZSet()).add(eq("waitlist:5"), eq("3"), anyDouble());
    }

    @Test
    void closeExpiredOrderByNoSkipsNonCreated() {
        TripOrder order = new TripOrder();
        order.setId(1L);
        order.setOrderNo("20261030151932907564");
        order.setTripId(5L);
        order.setPassengerId(2L);
        order.setSeatCount(1);
        order.setStatus("PAID"); // 已支付，静默跳过
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        service.closeExpiredOrderByNo("20261030151932907564");

        verify(orderMapper, never()).update(any(TripOrder.class), any());
        verify(tripSeatMapper, never()).restoreSeat(anyLong(), anyInt());
    }

    @Test
    void closeExpiredOrderByNoClosesCreatedOrder() {
        TripOrder order = new TripOrder();
        order.setId(1L);
        order.setOrderNo("20261030151932907564");
        order.setTripId(5L);
        order.setPassengerId(2L);
        order.setSeatCount(2);
        order.setStatus("CREATED");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(tripSeatMapper.restoreSeat(5L, 2)).thenReturn(1);
        when(redisTemplate.opsForZSet().range(anyString(), eq(0L), eq(0L))).thenReturn(Set.of());

        service.closeExpiredOrderByNo("20261030151932907564");

        ArgumentCaptor<TripOrder> captor = ArgumentCaptor.forClass(TripOrder.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals("EXPIRED", captor.getValue().getStatus());
        assertEquals("超时未支付自动关闭", captor.getValue().getCancelReason());
        verify(tripSeatMapper).restoreSeat(5L, 2);
        // Redis 回补
        verify(redisTemplate, atLeastOnce()).execute(any(), anyList(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createWithCouponSettlesDiscount() {
        mockFetchTrip(futureTrip());
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(3L);
        when(tripSeatMapper.deductSeat(5L, 1)).thenReturn(1);
        // 券服务结算：60 元 - 5 元券 = 55 元实付
        when(restTemplate.exchange(contains("/api/coupon/settle"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(
                        new com.goshare.order.dto.CouponSettleResult(
                                new BigDecimal("5.00"), new BigDecimal("55.00")))));

        OrderCreateRequest req = createReq();
        req.setCouponId(101L);
        OrderVO vo = service.create(2L, req);

        assertEquals(0, vo.getCouponDiscount().compareTo(new BigDecimal("5.00")));
        assertEquals(0, vo.getPayPrice().compareTo(new BigDecimal("55.00")));
        assertEquals("CREATED", vo.getStatus());
        verify(idempotencyMapper).insert(any(IdempotencyRecord.class));
    }

    // ---------- F4 改签 ----------

    private TripOrder paidOrder() {
        TripOrder order = new TripOrder();
        order.setId(9L);
        order.setOrderNo("20261105140742233293");
        order.setPassengerId(3L);
        order.setTripId(5L);
        order.setSeatCount(1);
        order.setFromStopSeq(0);
        order.setToStopSeq(1);
        order.setTotalPrice(new BigDecimal("60"));
        order.setPayPrice(new BigDecimal("60"));
        order.setStatus("PAID");
        return order;
    }

    private TripDTO paidOrderTrip() {
        TripDTO trip = futureTrip();
        trip.setId(5L);
        trip.setFromCity("石家庄");
        trip.setToCity("邯郸");
        return trip;
    }

    @Test
    @SuppressWarnings("unchecked")
    void rescheduleSuccessRefundsDiff() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(paidOrder());
        // 原行程与目标行程（同线路、未来、可预订）
        TripDTO oldTrip = paidOrderTrip();
        TripDTO newTrip = paidOrderTrip();
        newTrip.setId(6L);
        newTrip.setDepartureTime(LocalDateTime.now().plusHours(3));
        when(restTemplate.exchange(contains("/api/trip/"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(oldTrip)), ResponseEntity.ok(Result.success(newTrip)));
        // 新区间库存充足（Redis 闸门成功；无区间数据走全程 DB 扣减）
        when(tripSegmentMapper.selectCoveredSegments(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(3L);
        when(tripSeatMapper.deductSeat(eq(6L), anyInt())).thenReturn(1);
        when(tripSeatMapper.restoreSeat(eq(5L), anyInt())).thenReturn(1);
        // CAS 更新成功
        when(orderMapper.update(any(), any())).thenReturn(1);

        OrderVO vo = service.reschedule(3L, "20261105140742233293", 6L, null, null);

        assertEquals(6L, vo.getTripId());
        // 新价 60 = 旧实付 60 → 无差额（refundAmount 为 null）
        assertNull(vo.getRefundAmount());
        // CAS 语句必须带 status=PAID 条件由实现保证；改签后原行程库存回补
        verify(tripSeatMapper).restoreSeat(eq(5L), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rescheduleRejectsDifferentRoute() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(paidOrder());
        TripDTO oldTrip = paidOrderTrip();
        TripDTO newTrip = paidOrderTrip();
        newTrip.setId(7L);
        newTrip.setFromCity("北京");
        newTrip.setToCity("上海");
        when(restTemplate.exchange(contains("/api/trip/"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(oldTrip)), ResponseEntity.ok(Result.success(newTrip)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reschedule(3L, "20261105140742233293", 7L, null, null));
        assertTrue(ex.getMessage().contains("同线路"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rescheduleRejectsWhenTargetSeatsInsufficient() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(paidOrder());
        TripDTO oldTrip = paidOrderTrip();
        TripDTO newTrip = paidOrderTrip();
        newTrip.setId(6L);
        newTrip.setDepartureTime(LocalDateTime.now().plusHours(3));
        when(restTemplate.exchange(contains("/api/trip/"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(oldTrip)), ResponseEntity.ok(Result.success(newTrip)));
        // Redis 闸门返回 -1（余座不足）
        when(tripSegmentMapper.selectCoveredSegments(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(-1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reschedule(3L, "20261105140742233293", 6L, null, null));
        assertTrue(ex.getMessage().contains("余座不足"));
        // 库存未动：订单不应被更新
        verify(orderMapper, never()).update(any(), any());
    }

    // ---------- 退款闭环（已支付订单取消） ----------

    @Test
    @SuppressWarnings("unchecked")
    void cancelPaidOrderCreatesRefundAndMarksRefunded() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(paidOrder());
        when(restTemplate.exchange(contains("/api/trip/"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(paidOrderTrip())));
        when(orderMapper.update(any(), any())).thenReturn(1);
        // 支付服务退款成功
        when(restTemplate.exchange(contains("/api/payment/refund"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(null)));

        OrderVO vo = service.cancel(3L, "20261105140742233293");

        // 已支付订单取消不再报错：落退款单 + 状态到 REFUNDED
        assertEquals("REFUNDED", vo.getStatus());
        assertNotNull(vo.getRefundNo());
        assertNotNull(vo.getRefundAmount());
        verify(refundOrderMapper).insert(any(com.goshare.order.entity.RefundOrder.class));
        verify(refundTransactionMapper).insert(any(com.goshare.order.entity.RefundTransaction.class));
        // 退款成功后回写退款单状态
        verify(refundOrderMapper).updateById(any(com.goshare.order.entity.RefundOrder.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelPaidOrderStaysRefundingWhenRefundFails() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(paidOrder());
        when(restTemplate.exchange(contains("/api/trip/"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(paidOrderTrip())));
        when(orderMapper.update(any(), any())).thenReturn(1);
        // 支付服务退款异常：订单停留在 REFUNDING，等对账重试（不能谎报已退款）
        when(restTemplate.exchange(contains("/api/payment/refund"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("payment down"));

        OrderVO vo = service.cancel(3L, "20261105140742233293");

        assertEquals("REFUNDING", vo.getStatus());
        verify(refundOrderMapper).updateById(any(com.goshare.order.entity.RefundOrder.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cancelPaidOrderDetectsConcurrentChange() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(paidOrder());
        when(restTemplate.exchange(contains("/api/trip/"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(paidOrderTrip())));
        // CAS 影响行数 0：并发下已被别的流程改状态
        when(orderMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancel(3L, "20261105140742233293"));

        assertEquals(409, ex.getCode());
    }

    // ---------- 履约：乘客名单 / 上车 / 到达 / 评价 ----------

    private TripDTO tripOwnedBy(long driverId) {
        TripDTO trip = paidOrderTrip();
        trip.setDriverId(driverId);
        return trip;
    }


    @Test
    void listByTripRejectsNonOwner() {
        mockFetchTrip(tripOwnedBy(1L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.listByTrip(2L, 5L));

        assertEquals(403, ex.getCode());
    }

    @Test
    void listByTripReturnsPassengersForOwner() {
        mockFetchTrip(tripOwnedBy(1L));
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(paidOrder()));

        List<OrderVO> list = service.listByTrip(1L, 5L);

        assertEquals(1, list.size());
        assertEquals("20261105140742233293", list.get(0).getOrderNo());
    }

    @Test
    void boardMovesPaidToBoarded() {
        mockFetchTrip(tripOwnedBy(1L));
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(paidOrder());
        when(orderMapper.update(any(), any())).thenReturn(1);

        OrderVO vo = service.board(1L, 5L, "20261105140742233293");

        assertEquals("BOARDED", vo.getStatus());
    }

    @Test
    void boardRejectsWrongStatus() {
        mockFetchTrip(tripOwnedBy(1L));
        TripOrder created = paidOrder();
        created.setStatus("CREATED");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(created);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.board(1L, 5L, "20261105140742233293"));

        assertEquals(400, ex.getCode());
    }

    @Test
    void completeMovesBoardedToCompleted() {
        mockFetchTrip(tripOwnedBy(1L));
        TripOrder boarded = paidOrder();
        boarded.setStatus("BOARDED");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(boarded);
        when(orderMapper.update(any(), any())).thenReturn(1);

        assertEquals("COMPLETED", service.complete(1L, 5L, "20261105140742233293").getStatus());
    }

    @Test
    void reviewMovesCompletedToReviewed() {
        mockFetchTrip(tripOwnedBy(1L));
        TripOrder completed = paidOrder();
        completed.setStatus("COMPLETED");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(completed);
        when(orderReviewMapper.insert(any(com.goshare.order.entity.OrderReview.class))).thenReturn(1);
        when(orderMapper.update(any(), any())).thenReturn(1);

        OrderVO vo = service.review(3L, "20261105140742233293", 5, "车很干净");

        assertEquals("REVIEWED", vo.getStatus());
        verify(orderReviewMapper).insert(any(com.goshare.order.entity.OrderReview.class));
    }

    @Test
    void reviewRejectsOutOfRangeRating() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(3L, "20261105140742233293", 6, "刷分"));
        assertEquals(400, ex.getCode());
        verify(orderReviewMapper, never()).insert(any(com.goshare.order.entity.OrderReview.class));
    }

    @Test
    void reviewRejectsNotCompletedOrder() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(paidOrder());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(3L, "20261105140742233293", 5, "还没到就评"));

        assertEquals(400, ex.getCode());
    }

    // ---------- F5 行程账单 / F3 价格参谋 ----------

    @Test
    void tripBillAggregatesRealOrders() {
        Map<String, Object> agg = Map.of(
                "orderCount", 2,
                "totalDistanceKm", new BigDecimal("807.7"),
                "totalPay", new BigDecimal("269.70"));
        when(orderMapper.aggregateBill(anyLong(), anyString(), anyString())).thenReturn(agg);
        when(orderMapper.groupByTrip(anyLong(), anyString(), anyString()))
                .thenReturn(List.of(Map.of("trip_id", 5L, "cnt", 2)));
        when(orderMapper.selectTripCities(5L))
                .thenReturn(Map.of("from_city", "石家庄", "to_city", "邯郸"));

        com.goshare.order.dto.TripBillVO bill = service.tripBill(3L, "2026-11");

        assertEquals("2026-11", bill.getMonth());
        assertEquals(2, bill.getOrderCount());
        assertEquals(0, bill.getTotalPay().compareTo(new BigDecimal("269.70")));
        // 网约车基准 2.3 元/公里：807.7 × 2.3 = 1857.71
        assertEquals(0, bill.getTaxiBenchmark().compareTo(new BigDecimal("1857.71")));
        assertEquals(1, bill.getTopRoutes().size());
        assertEquals("石家庄", bill.getTopRoutes().get(0).getFromCity());
    }

    @Test
    void tripBillRejectsBadMonth() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service.tripBill(3L, "2026/11"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void priceStatsComputesAvgMinMedian() {
        when(priceStatsMapper.selectTripIds("石家庄", "邯郸")).thenReturn(List.of(5L, 6L));
        when(priceStatsMapper.selectPayPrices(anyList(), eq(30)))
                .thenReturn(List.of(new BigDecimal("40"), new BigDecimal("60"), new BigDecimal("80")));

        com.goshare.order.dto.PriceStatsVO stats = service.priceStats("石家庄", "邯郸", 30);

        assertEquals(3, stats.getOrderCount());
        assertEquals(0, stats.getAvgPrice().compareTo(new BigDecimal("60.00")));
        assertEquals(0, stats.getMinPrice().compareTo(new BigDecimal("40")));
        assertEquals(0, stats.getMedianPrice().compareTo(new BigDecimal("60")));
    }

    @Test
    void priceStatsReturnsEmptyWhenNoTrips() {
        when(priceStatsMapper.selectTripIds(anyString(), anyString())).thenReturn(List.of());

        com.goshare.order.dto.PriceStatsVO stats = service.priceStats("石家庄", "邯郸", 30);

        assertEquals(0, stats.getOrderCount());
        assertNull(stats.getMedianPrice());
    }
}
