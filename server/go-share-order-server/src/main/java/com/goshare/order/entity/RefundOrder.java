package com.goshare.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款单实体（对应表 refund_order）
 *
 * <p>阶段2「退款域」：已支付订单取消后产生退款单，状态机
 * APPLYING → APPROVED → REFUNDING → SUCCESS / FAILED。
 */
@Data
@TableName("refund_order")
public class RefundOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 退款单号 */
    private String refundNo;

    /** 原订单号 */
    private String orderNo;

    /** 乘客ID */
    private Long passengerId;

    /** 原支付流水号 */
    private String paymentNo;

    /** 退款金额（元） */
    private BigDecimal refundAmount;

    /** 扣除的违约金（元） */
    private BigDecimal feeAmount;

    /** 退款原因 */
    private String reason;

    /** APPLYING申请中 APPROVED已审批 REFUNDING退款中 SUCCESS成功 FAILED失败 */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime refundedAt;

    // ---- 退款状态常量 ----
    public static final String STATUS_APPLYING = "APPLYING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REFUNDING = "REFUNDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
}
