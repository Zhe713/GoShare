package com.goshare.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体（对应表 trip_order）
 */
@Data
@TableName("trip_order")
public class TripOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号 */
    private String orderNo;

    /** 行程ID */
    private Long tripId;

    /** 乘客用户ID */
    private Long passengerId;

    /** 购买座位数 */
    private Integer seatCount;

    /** 订单总额（元） */
    private BigDecimal totalPrice;

    /** 券优惠金额 */
    private BigDecimal couponDiscount;

    /** 实付金额（元） */
    private BigDecimal payPrice;

    /** 订单状态 CREATED待支付 PAID已支付 IN_SERVICE服务中 COMPLETED已完成 CANCELLED已取消 EXPIRED超时关闭 */
    private String status;

    /** 支付流水号 */
    private String paymentNo;

    /** 取消原因 */
    private String cancelReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
