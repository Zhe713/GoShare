package com.goshare.payment.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付信息（返回给前端）
 */
@Data
public class PaymentVO {

    private String paymentNo;

    private String orderNo;

    private Long passengerId;

    private BigDecimal amount;

    /** PENDING待支付 SUCCESS成功 FAILED失败 REFUNDED已退款 */
    private String status;

    private String channel;

    private LocalDateTime createdAt;

    private LocalDateTime paidAt;
}
