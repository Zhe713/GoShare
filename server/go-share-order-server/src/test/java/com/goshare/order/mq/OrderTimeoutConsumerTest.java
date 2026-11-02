package com.goshare.order.mq;

import com.goshare.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 订单超时延时消息消费：Redis 原子去重，重复消息静默跳过
 */
class OrderTimeoutConsumerTest {

    private OrderService orderService;
    private StringRedisTemplate redisTemplate;
    private OrderTimeoutConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderService = mock(OrderService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        consumer = new OrderTimeoutConsumer(orderService, redisTemplate);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void firstConsumeClosesExpiredOrder() {
        when(redisTemplate.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        consumer.onMessage("20261030151932907564");

        verify(redisTemplate.opsForValue()).setIfAbsent(
                eq("order:timeout:consume:20261030151932907564"), eq("1"), eq(Duration.ofHours(1)));
        verify(orderService).closeExpiredOrderByNo("20261030151932907564");
    }

    @Test
    void duplicateMessageSkipped() {
        when(redisTemplate.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);

        consumer.onMessage("20261030151932907564");

        verify(orderService, never()).closeExpiredOrderByNo(anyString());
    }
}
