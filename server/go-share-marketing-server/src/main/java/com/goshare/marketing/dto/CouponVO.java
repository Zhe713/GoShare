package com.goshare.marketing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户券信息（返回给前端）
 */
@Data
public class CouponVO {

    private Long id;

    private String couponNo;

    private Long couponTemplateId;

    /** FULL_REDUCTION满减 DISCOUNT折扣 CASH立减 */
    private String type;

    private BigDecimal threshold;

    private BigDecimal discountValue;

    /** 0未用 1已用 2已过期 3锁定中 */
    private Integer status;

    private String orderNo;

    private LocalDateTime receivedAt;

    private LocalDateTime usedAt;

    private LocalDateTime expireAt;
}
