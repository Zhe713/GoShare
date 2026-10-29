package com.goshare.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 取消订单请求
 */
@Data
public class OrderCancelRequest {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;
}
