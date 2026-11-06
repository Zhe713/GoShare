package com.goshare.trip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行程实体（对应表 trip）
 *
 * <p>阶段1「换骨」后，行程承载了完整路线信息：站点序列（trip_stop）、区间库存（trip_segment）、
 * 车辆快照（车牌/车型/颜色/车主昵称）。计价从"司机手填单价"改为"按区间里程 × 每公里单价"。
 */
@Data
@TableName("trip")
public class Trip {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 车主用户ID */
    private Long driverId;

    /** 出发城市 */
    private String fromCity;

    /** 到达城市 */
    private String toCity;

    /** 出发时间 */
    private LocalDateTime departureTime;

    /** 总座位数 */
    private Integer totalSeats;

    /** 剩余座位数（全程最小可用余座，冗余字段，实时以 trip_segment 为准） */
    private Integer remainSeats;

    /** 每人价格（元，兼容旧字段；区间计价启用后由 price_per_km × distance 计算） */
    private BigDecimal price;

    /** 每公里单价（元），区间计价自变量（阶段1新增） */
    private BigDecimal pricePerKm;

    /** 整段里程（公里），发布时按站点坐标计算（阶段1新增） */
    private BigDecimal totalDistanceKm;

    /** 行程状态 0待出发 1满座 2进行中 3已完成 4已取消（阶段1重定义） */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 车牌号（发布时快照，阶段1新增） */
    private String vehiclePlate;

    /** 车型（发布时快照，阶段1新增） */
    private String vehicleModel;

    /** 车身颜色（发布时快照，阶段1新增） */
    private String vehicleColor;

    /** 车主昵称快照（下单前展示信任信息，阶段1新增） */
    private String driverNickname;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ---- 行程状态常量（阶段1重定义状态机） ----
    public static final int STATUS_PUBLISHED = 0; // 待出发（可预订）
    public static final int STATUS_FULL = 1;      // 满座（无余座）
    public static final int STATUS_DEPARTED = 2;  // 进行中（已发车）
    public static final int STATUS_COMPLETED = 3; // 已完成
    public static final int STATUS_CANCELLED = 4; // 已取消
}
