package com.goshare.trip.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行程信息（返回给前端）
 */
@Data
public class TripVO {

    private Long id;

    private Long driverId;

    private String fromCity;

    private String toCity;

    private LocalDateTime departureTime;

    private Integer totalSeats;

    private Integer remainSeats;

    private BigDecimal price;

    /** 0待出发 1进行中 2已完成 3已取消 */
    private Integer status;

    private String remark;

    private LocalDateTime createdAt;
}
