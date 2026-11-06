package com.goshare.payment.service;

import com.goshare.payment.dto.PaymentVO;

import java.math.BigDecimal;

/**
 * 支付服务
 */
public interface PaymentService {

    /** 发起模拟支付：校验订单 → 扣款成功 → 回调订单服务标记已支付 */
    PaymentVO pay(Long passengerId, String orderNo);

    /** 按支付流水号查询 */
    PaymentVO getByPaymentNo(String paymentNo);

    /** 退款（阶段2退款域）：按支付流水号退款，演示渠道 MOCK 立即成功 */
    boolean refund(String paymentNo, BigDecimal amount);
}
