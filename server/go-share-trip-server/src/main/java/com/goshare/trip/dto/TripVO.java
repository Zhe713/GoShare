package com.goshare.trip.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 行程信息（返回给前端）
 *
 * <p>阶段1「换骨」后，行程 VO 携带乘客下单前的完整决策信息：
 * 车辆快照（车牌/车型/颜色）、车主昵称、站点序列、区间余座、每公里单价、整段里程。
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

    /** 每人价格（元，兼容字段） */
    private BigDecimal price;

    /** 每公里单价（元，区间计价） */
    private BigDecimal pricePerKm;

    /** 整段里程（公里） */
    private BigDecimal totalDistanceKm;

    /** 0待出发 1满座 2进行中 3已完成 4已取消 */
    private Integer status;

    private String remark;

    /** 车牌号 */
    private String vehiclePlate;

    /** 车型 */
    private String vehicleModel;

    /** 车身颜色 */
    private String vehicleColor;

    /** 车主昵称快照 */
    private String driverNickname;

    /** 站点序列（含起终点与途经点） */
    private List<TripStopDTO> stops;

    private LocalDateTime createdAt;
}
