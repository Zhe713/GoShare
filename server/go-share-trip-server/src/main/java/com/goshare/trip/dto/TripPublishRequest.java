package com.goshare.trip.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发布行程请求
 */
@Data
public class TripPublishRequest {

    @NotBlank(message = "出发城市不能为空")
    private String fromCity;

    @NotBlank(message = "到达城市不能为空")
    private String toCity;

    @NotNull(message = "出发时间不能为空")
    private LocalDateTime departureTime;

    @NotNull(message = "总座位数不能为空")
    @Min(value = 1, message = "座位数至少 1")
    private Integer totalSeats;

    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格至少 0.01 元")
    private BigDecimal price;

    private String remark;
}
