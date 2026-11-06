package com.goshare.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款流水实体（对应表 refund_transaction）
 */
@Data
@TableName("refund_transaction")
public class RefundTransaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 退款单号 */
    private String refundNo;

    /** 原订单号 */
    private String orderNo;

    /** 原支付流水号 */
    private String paymentNo;

    /** 退款渠道 */
    private String channel;

    /** 退款金额（元） */
    private BigDecimal amount;

    /** PENDING处理中 SUCCESS成功 FAILED失败 */
    private String status;

    /** 渠道退款流水号 */
    private String channelRefNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
