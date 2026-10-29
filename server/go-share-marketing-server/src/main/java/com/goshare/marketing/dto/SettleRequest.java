package com.goshare.marketing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 用券结算请求（订单服务下单时调用）
 */
@Data
public class SettleRequest {

    /** 券实例ID */
    @NotNull(message = "券ID不能为空")
    private Long couponId;

    /** 用券人用户ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 订单号 */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /** 订单原总额（元） */
    @NotNull(message = "订单总额不能为空")
    private BigDecimal totalPrice;
}
