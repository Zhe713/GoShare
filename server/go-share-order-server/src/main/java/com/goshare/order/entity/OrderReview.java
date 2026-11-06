package com.goshare.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单评价实体（对应表 order_review）
 *
 * <p>行程完成的履约闭环产物：乘客评价车主，派生信任分。
 */
@Data
@TableName("order_review")
public class OrderReview {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号 */
    private String orderNo;

    /** 评价人（乘客）ID */
    private Long reviewerId;

    /** 被评价人（车主）ID */
    private Long revieweeId;

    /** 行程ID */
    private Long tripId;

    /** 评分 1-5 */
    private Integer rating;

    /** 评价内容 */
    private String comment;

    private LocalDateTime createdAt;
}
