package com.goshare.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 行程站点（订单服务从行程服务获取，用于区间计价与库存）
 */
@Data
public class TripStopDTO {

    private String city;

    private String address;

    private BigDecimal lng;

    private BigDecimal lat;

    private Integer offsetMinutes;
}
