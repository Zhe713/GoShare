package com.goshare.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订单服务核心链路：下单幂等/余座校验/取消回补递补/支付回调幂等/超时关闭/候补登记
 */
class OrderServiceImplTest {

    private TripOrderMapper orderMapper;
    private IdempotencyRecordMapper idempotencyMapper;
    private TripSeatMapper tripSeatMapper;
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
        redisTemplate = mock(StringRedisTemplate.class);
        restTemplate = mock(RestTemplate.class);
        objectMapper = new ObjectMapper();
        rocketMQTemplate = mock(RocketMQTemplate.class);
        service = new OrderServiceImpl(orderMapper, idempotencyMapper, tripSeatMapper,
                redisTemplate, restTemplate, objectMapper, rocketMQTemplate);
        ReflectionTestUtils.setField(service, "tripServiceUrl", "http://trip:8002");
        ReflectionTestUtils.setField(service, "timeoutMinutes", 10L);
        ReflectionTestUtils.setField(service, "scheduledTimeoutMinutes", 12L);
        ReflectionTestUtils.setField(service, "marketingServiceUrl", "http://marketing:8004");
        when(redisTemplate.opsForZSet()).thenReturn(mock(ZSetOperations.class));
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
    void cancelRejectsNonCreatedOrder() {
        TripOrder order = new TripOrder();
        order.setId(1L);
        order.setOrderNo("20261030151932907564");
        order.setPassengerId(2L);
        order.setTripId(5L);
        order.setSeatCount(1);
        order.setStatus("PAID");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.cancel(2L, "20261030151932907564"));
        assertEquals(400, ex.getCode());
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
        when(idempotencyMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);
        when(orderMapper.update(any(), any())).thenReturn(1);

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
}
