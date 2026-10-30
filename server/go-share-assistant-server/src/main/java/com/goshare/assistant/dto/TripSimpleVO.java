package com.goshare.assistant.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * trip 服务返回的行程轻量信息（assistant 侧解析用）
 */
@Data
public class TripSimpleVO {

    private Long id;

    private Long driverId;

    private String fromCity;

    private String toCity;

    private LocalDateTime departureTime;

    private Integer remainSeats;

    private BigDecimal price;

    private Integer status;

    private String remark;
}
