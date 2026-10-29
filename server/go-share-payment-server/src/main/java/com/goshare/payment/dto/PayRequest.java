package com.goshare.payment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发起支付请求
 */
@Data
public class PayRequest {

    /** 订单号 */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;
}
