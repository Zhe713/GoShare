package com.goshare.assistant.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * order 服务返回的订单详情（assistant 侧解析：A2 替代方案需要 tripId/状态）
 */
@Data
public class OrderDetailVO {

    private String orderNo;

    private Long tripId;

    private Long passengerId;

    private Integer seatCount;

    private String totalPrice;

    private String payPrice;

    private String status;
}
