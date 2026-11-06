package com.goshare.trip.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 行程站点（发布/展示用）
 */
@Data
public class TripStopDTO {

    /** 城市（起点/途经/终点） */
    @NotBlank(message = "站点城市不能为空")
    private String city;

    /** 具体地址（上车/下车点，可选） */
    private String address;

    /** 经度（可选，缺省时按里程表估算） */
    @DecimalMin(value = "-180", message = "经度非法")
    @DecimalMax(value = "180", message = "经度非法")
    private BigDecimal lng;

    /** 纬度（可选） */
    @DecimalMin(value = "-90", message = "纬度非法")
    @DecimalMax(value = "90", message = "纬度非法")
    private BigDecimal lat;

    /** 相对发车时间的预估到达分钟数（可选，缺省按里程/均速估算） */
    @Min(value = 0, message = "到达分钟数不能为负")
    private Integer offsetMinutes;
}
