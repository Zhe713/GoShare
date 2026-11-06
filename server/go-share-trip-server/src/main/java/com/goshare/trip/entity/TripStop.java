package com.goshare.trip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行程站点实体（对应表 trip_stop）
 *
 * <p>行程是一条带时间和容量的折线，站点序列是它的固有属性。每个站点有城市、具体地址、
 * 经纬度、以及相对发车时间的预估到达分钟数。
 */
@Data
@TableName("trip_stop")
public class TripStop {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 行程ID */
    private Long tripId;

    /** 站点序号（0=起点） */
    private Integer seq;

    /** 城市 */
    private String city;

    /** 具体地址（上车/下车点） */
    private String address;

    /** 经度 */
    private BigDecimal lng;

    /** 纬度 */
    private BigDecimal lat;

    /** 相对发车时间的预估到达分钟数 */
    private Integer offsetMinutes;

    private LocalDateTime createdAt;
}
