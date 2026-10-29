package com.goshare.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 券变动流水实体（对应表 coupon_log）
 */
@Data
@TableName("coupon_log")
public class CouponLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long couponId;

    /** 动作 RECEIVE领取 LOCK锁定 USE用券 RETURN退券 EXPIRE过期 */
    private String action;

    /** 关联订单号 */
    private String orderNo;

    /** 涉及金额（元） */
    private BigDecimal amount;

    private String remark;

    private LocalDateTime createdAt;
}
