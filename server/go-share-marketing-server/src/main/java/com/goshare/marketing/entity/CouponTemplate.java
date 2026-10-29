package com.goshare.marketing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 券模板实体（对应表 coupon_template）
 */
@Data
@TableName("coupon_template")
public class CouponTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 券名称 */
    private String templateName;

    /** 券类型 FULL_REDUCTION满减 DISCOUNT折扣 CASH立减 */
    private String type;

    /** 满X元可用（0=无门槛） */
    private BigDecimal threshold;

    /** 满减/立减金额（元），折扣券为折扣率（如85=8.5折） */
    private BigDecimal discountValue;

    /** 发行总量（0=不限量） */
    private Integer totalCount;

    /** 已领取数量 */
    private Integer receivedCount;

    /** 领取开始时间 */
    private LocalDateTime startTime;

    /** 领取结束时间 */
    private LocalDateTime endTime;

    /** 领取后有效期（天） */
    private Integer expireDays;

    /** 状态 0上架 1下架 */
    private Integer status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
