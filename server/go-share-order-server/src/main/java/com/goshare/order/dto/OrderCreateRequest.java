package com.goshare.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 下单请求
 */
@Data
public class OrderCreateRequest {

    @NotNull(message = "行程ID不能为空")
    private Long tripId;

    @NotNull(message = "座位数不能为空")
    @Min(value = 1, message = "座位数至少 1")
    private Integer seatCount;

    /**
     * 幂等键：前端生成，防重复下单。
     * 上限 64 与 idempotency_key 表的 varchar(64) 对齐——超长会被 MySQL 截断报
     * Data too long，进而变成 500「系统繁忙」，这里提前拦成 400 并给出可读提示。
     */
    @NotBlank(message = "幂等键不能为空")
    @Size(max = 64, message = "幂等键长度不能超过 64 个字符")
    private String idempotentKey;

    /** 优惠券ID（可选，下单用券抵扣） */
    private Long couponId;

    /** 上车站点序号（阶段1区间库存，可选；null=全程从起点） */
    private Integer fromStopSeq;

    /** 下车站点序号（阶段1区间库存，可选；null=全程到终点） */
    private Integer toStopSeq;
}
