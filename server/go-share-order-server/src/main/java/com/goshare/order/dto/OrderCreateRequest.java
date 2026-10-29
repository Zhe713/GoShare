package com.goshare.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    /** 幂等键：前端生成，防重复下单 */
    @NotBlank(message = "幂等键不能为空")
    private String idempotentKey;

    /** 优惠券ID（可选，下单用券抵扣） */
    private Long couponId;
}
