package com.goshare.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 线路价格统计（F3 价格参谋）
 */
@Data
public class PriceStatsVO {

    private String fromCity;
    private String toCity;
    /** 统计窗口（天） */
    private Integer days;
    /** 近 N 天订单数 */
    private Integer orderCount;
    /** 均价（元） */
    private BigDecimal avgPrice;
    /** 最低价（元） */
    private BigDecimal minPrice;
    /** 中位数（元） */
    private BigDecimal medianPrice;
}
