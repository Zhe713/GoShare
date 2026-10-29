package com.goshare.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户手里的券实体（对应表 user_coupon）
 */
@Data
@TableName("user_coupon")
public class UserCoupon {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 券号（防重复） */
    private String couponNo;

    /** 模板ID */
    private Long couponTemplateId;

    /** 所属用户ID */
    private Long userId;

    /** 券类型（冗余模板快照） */
    private String type;

    /** 满X元可用（冗余快照） */
    private BigDecimal threshold;

    /** 折扣金额/率（冗余快照） */
    private BigDecimal discountValue;

    /** 状态 0未用 1已用 2已过期 3锁定中 */
    private Integer status;

    /** 用在哪张订单 */
    private String orderNo;

    /** 领取时间 */
    private LocalDateTime receivedAt;

    /** 使用时间 */
    private LocalDateTime usedAt;

    /** 可用截止时间 */
    private LocalDateTime expireAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
