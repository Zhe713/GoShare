package com.goshare.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付记录实体（对应表 payment）
 */
@Data
@TableName("payment")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 支付流水号 */
    private String paymentNo;

    /** 订单号 */
    private String orderNo;

    /** 乘客用户ID */
    private Long passengerId;

    /** 支付金额（元） */
    private BigDecimal amount;

    /** 支付状态 PENDING待支付 SUCCESS成功 FAILED失败 REFUNDED已退款 */
    private String status;

    /** 支付渠道（演示用模拟） */
    private String channel;

    private LocalDateTime createdAt;

    /** 支付成功时间 */
    private LocalDateTime paidAt;

    private LocalDateTime updatedAt;
}
