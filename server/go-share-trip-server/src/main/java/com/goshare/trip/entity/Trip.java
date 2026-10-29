package com.goshare.trip.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行程实体（对应表 trip）
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

    /** 剩余座位数 */
    private Integer remainSeats;

    /** 每人价格（元） */
    private BigDecimal price;

    /** 行程状态 0待出发 1进行中 2已完成 3已取消 */
    private Integer status;

    /** 备注 */
    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
