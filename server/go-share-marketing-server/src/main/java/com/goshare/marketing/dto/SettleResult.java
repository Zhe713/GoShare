package com.goshare.marketing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 用券结算结果（返回给订单服务）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SettleResult {

    /** 券抵扣金额（元） */
    private BigDecimal discount;

    /** 用券后实付金额（元） */
    private BigDecimal payPrice;
}
