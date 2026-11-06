package com.goshare.trip.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 发布行程请求
 *
 * <p>阶段1「换骨」后，发布请求支持站点序列（路线折线）、车辆快照（车牌/车型/颜色）、
 * 每公里单价。这些是乘客"上谁的车、在哪上车、几点到"的决策信息。
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

    /**
     * 每人价格（元，兼容旧字段）。
     * 当未提供 pricePerKm 且未提供站点时，沿用旧计价：price × seatCount。
     */
    @DecimalMin(value = "0.01", message = "价格至少 0.01 元")
    private BigDecimal price;

    /** 每公里单价（元），区间计价自变量（阶段1新增，可选） */
    @DecimalMin(value = "0.01", message = "每公里单价至少 0.01 元")
    private BigDecimal pricePerKm;

    /** 站点序列（阶段1新增，可选；不传则退化为起终点两站，全程一段） */
    @Valid
    @Size(min = 2, max = 20, message = "站点数须在 2~20 之间")
    private List<TripStopDTO> stops;

    /** 车牌号（阶段1新增，可选） */
    private String vehiclePlate;

    /** 车型（阶段1新增，可选） */
    private String vehicleModel;

    /** 车身颜色（阶段1新增，可选） */
    private String vehicleColor;

    private String remark;
}
