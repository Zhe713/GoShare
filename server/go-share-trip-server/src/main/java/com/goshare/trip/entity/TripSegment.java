package com.goshare.trip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 区间库存实体（对应表 trip_segment）
 *
 * <p>区间覆盖计数：座位只在订单覆盖的区间内扣减，而非全程扣减。
 * 例如「北京→济南→上海」两个区间各 4 座，乘客买「北京→济南」只扣第一个区间，
 * 「济南→上海」仍可售 4 座——这是顺风车业务与"全程一个标量余座"的本质区别。
 */
@Data
@TableName("trip_segment")
public class TripSegment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 行程ID */
    private Long tripId;

    /** 区间起点站序号 */
    private Integer fromSeq;

    /** 区间终点站序号 */
    private Integer toSeq;

    /** 区间里程（公里） */
    private BigDecimal distanceKm;

    /** 区间剩余座位 */
    private Integer remainSeats;

    /** 区间总座位（=行程总座位） */
    private Integer totalSeats;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
