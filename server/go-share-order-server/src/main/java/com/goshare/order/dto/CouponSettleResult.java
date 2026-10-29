package com.goshare.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 券结算结果（订单服务通过 HTTP 调用券服务获取）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponSettleResult {

    /** 券抵扣金额（元） */
    private BigDecimal discount;

    /** 用券后实付金额（元） */
    private BigDecimal payPrice;
}
