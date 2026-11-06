package com.goshare.payment.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单信息（支付服务通过 HTTP 调用订单服务获取）
 */
@Data
public class OrderDTO {

    private String orderNo;

    private Long tripId;

    /** 下单乘客（用于校验"只能给自己的订单付款"） */
    private Long passengerId;

    private BigDecimal payPrice;

    /** CREATED待支付 PAID已支付 ... */
    private String status;
}
