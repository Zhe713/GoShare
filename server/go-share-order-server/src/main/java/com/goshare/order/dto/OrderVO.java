package com.goshare.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单信息（返回给前端）
 */
@Data
public class OrderVO {

    private Long id;

    private String orderNo;

    private Long tripId;

    private Long passengerId;

    private Integer seatCount;

    private BigDecimal totalPrice;

    private BigDecimal payPrice;

    /** CREATED待支付 PAID已支付 IN_SERVICE服务中 COMPLETED已完成 CANCELLED已取消 EXPIRED超时关闭 */
    private String status;

    private String paymentNo;

    private String cancelReason;

    private LocalDateTime createdAt;
}
