package com.goshare.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行程信息（订单服务通过 HTTP 调用行程服务获取，仅取用所需字段）
 */
@Data
public class TripDTO {

    private Long id;

    private String fromCity;

    private String toCity;

    private LocalDateTime departureTime;

    private BigDecimal price;

    private Integer remainSeats;

    /** 0待出发 1进行中 2已完成 3已取消 */
    private Integer status;
}
