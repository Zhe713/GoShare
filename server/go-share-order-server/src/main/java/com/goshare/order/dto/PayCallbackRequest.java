package com.goshare.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 支付回调请求（由支付服务调用）
 */
@Data
public class PayCallbackRequest {

    /** 订单号 */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /** 支付流水号 */
    @NotBlank(message = "支付流水号不能为空")
    private String paymentNo;
}
