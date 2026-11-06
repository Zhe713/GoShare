package com.goshare.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 行程信息（订单服务通过 HTTP 调用行程服务获取，仅取用所需字段）
 *
 * <p>阶段1「换骨」后，订单服务需要行程的站点序列、每公里单价、整段里程，
 * 以支持区间计价与区间库存扣减。
 */
@Data
public class TripDTO {

    private Long id;

    /** 车主ID */
    private Long driverId;

    private String fromCity;

    private String toCity;

    private LocalDateTime departureTime;

    private BigDecimal price;

    /** 每公里单价（元，区间计价） */
    private BigDecimal pricePerKm;

    /** 整段里程（公里） */
    private BigDecimal totalDistanceKm;

    private Integer remainSeats;

    /** 0待出发 1满座 2进行中 3已完成 4已取消 */
    private Integer status;

    /** 车牌号 */
    private String vehiclePlate;

    /** 车型 */
    private String vehicleModel;

    /** 车主昵称 */
    private String driverNickname;

    /** 站点序列（阶段1新增，供订单绑定上下车站点、计算区间里程） */
    private List<TripStopDTO> stops;
}
