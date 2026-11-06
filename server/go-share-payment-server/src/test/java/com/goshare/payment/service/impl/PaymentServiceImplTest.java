package com.goshare.payment.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.common.result.Result;
import com.goshare.payment.dto.OrderDTO;
import com.goshare.payment.dto.PaymentVO;
import com.goshare.payment.entity.Payment;
import com.goshare.payment.mapper.PaymentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 支付服务：模拟支付/订单回调/回调失败置 FAILED/重复支付拦截
 */
class PaymentServiceImplTest {

    private PaymentMapper paymentMapper;
    private RestTemplate restTemplate;
    private PaymentServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        paymentMapper = mock(PaymentMapper.class);
        restTemplate = mock(RestTemplate.class);
        service = new PaymentServiceImpl(paymentMapper, restTemplate);
        ReflectionTestUtils.setField(service, "orderServiceUrl", "http://order:8003");
    }

    private OrderDTO createdOrder() {
        OrderDTO dto = new OrderDTO();
        dto.setOrderNo("20261030151932907564");
        dto.setPayPrice(new BigDecimal("40"));
        dto.setStatus("CREATED");
        return dto;
    }

    @Test
    void payOrderNotFound() {
        when(restTemplate.exchange(eq("http://order:8003/api/order/internal/20261030151932907564"),
                any(), any(), any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(Result.success(null)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.pay(2L, "20261030151932907564"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void payRejectsNonCreatedOrder() {
        OrderDTO paid = createdOrder();
        paid.setStatus("PAID");
        when(restTemplate.exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(paid)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.pay(2L, "20261030151932907564"));
        assertEquals(400, ex.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void paySuccessMarksPaid() {
        OrderDTO dto = createdOrder();
        when(restTemplate.exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
                .thenAnswer(inv -> {
                    String url = inv.getArgument(0);
                    if (url.contains("/api/order/")) {
                        return ResponseEntity.ok(Result.success(dto));
                    }
                    if (url.contains("/pay-callback")) {
                        return ResponseEntity.ok(Result.success(null));
                    }
                    return ResponseEntity.ok(Result.success(null));
                });
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(paymentMapper.insert(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(1L);
            return 1;
        });

        PaymentVO vo = service.pay(2L, "20261030151932907564");

        assertEquals("SUCCESS", vo.getStatus());
        assertEquals("MOCK", vo.getChannel());
        assertNotNull(vo.getPaymentNo());
        // 成功路径仅一次 updateById（PENDING 记录由 insert 建立）
        verify(paymentMapper, times(1)).updateById(any(Payment.class));
    }

    /**
     * 回调失败时**不能**把已成功扣款的流水改成 FAILED。
     *
     * <p>旧实现是"回调失败 → 置 FAILED"，但它同时又带 @Transactional，
     * 抛出异常后整个事务回滚，这条 FAILED 记录实际根本不存在（旧测试断言的是一个幻觉状态）。
     * 现在的语义是：钱已经扣了，流水必须保持 SUCCESS，订单侧由补偿/对账补齐，
     * 否则真实渠道下就是"已扣款、记失败、资损"。
     */
    @Test
    @SuppressWarnings("unchecked")
    void payCallbackFailureKeepsSuccessRecord() {
        OrderDTO dto = createdOrder();
        when(restTemplate.exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
                .thenAnswer(inv -> {
                    String url = inv.getArgument(0);
                    if (url.contains("/pay-callback")) {
                        return ResponseEntity.ok(Result.error(500, "order down")); // 回调失败
                    }
                    if (url.contains("/api/order/")) {
                        return ResponseEntity.ok(Result.success(dto));
                    }
                    return ResponseEntity.ok(Result.error(500, "order down"));
                });
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.pay(2L, "20261030151932907564"));
        assertEquals(500, ex.getCode());
        // 流水保持 SUCCESS（资金事实不因业务同步失败被抹掉）
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper, times(1)).updateById(captor.capture());
        assertEquals("SUCCESS", captor.getValue().getStatus());
        assertNotNull(captor.getValue().getPaidAt());
    }

    @Test
    void getByPaymentNoNotFound() {
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getByPaymentNo("P1"));
        assertEquals(404, ex.getCode());
    }
}
