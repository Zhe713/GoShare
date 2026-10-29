package com.goshare.payment.service;

import com.goshare.payment.dto.PaymentVO;

/**
 * 支付服务
 */
public interface PaymentService {

    /** 发起模拟支付：校验订单 → 扣款成功 → 回调订单服务标记已支付 */
    PaymentVO pay(Long passengerId, String orderNo);

    /** 按支付流水号查询 */
    PaymentVO getByPaymentNo(String paymentNo);
}
