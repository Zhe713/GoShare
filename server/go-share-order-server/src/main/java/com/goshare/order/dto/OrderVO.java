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

    /** 上车站点序号（阶段1区间库存） */
    private Integer fromStopSeq;

    /** 下车站点序号（阶段1区间库存） */
    private Integer toStopSeq;

    /** 订单区间里程（公里） */
    private BigDecimal distanceKm;

    private BigDecimal totalPrice;

    private BigDecimal couponDiscount;

    /** 使用的券ID */
    private Long couponId;

    private BigDecimal payPrice;

    /** CREATED待支付 PAID已支付 IN_SERVICE服务中 COMPLETED已完成 CANCELLED已取消 EXPIRED超时关闭 */
    private String status;

    private String paymentNo;

    private String cancelReason;

    /** 退款单号（已支付订单取消后产生，阶段2退款域） */
    private String refundNo;

    /** 退款金额（元） */
    private BigDecimal refundAmount;

    private LocalDateTime createdAt;

    /** 行程摘要（列表/详情展示用，来自行程服务） */
    private String tripFromCity;

    private String tripToCity;

    private LocalDateTime tripDepartureTime;
}
