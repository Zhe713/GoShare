package com.goshare.marketing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 券操作请求（确认用券 / 退券，由订单服务调用）
 */
@Data
public class CouponActionRequest {

    /** 券实例ID */
    @NotNull(message = "券ID不能为空")
    private Long couponId;

    /** 用户ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 订单号 */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;
}
