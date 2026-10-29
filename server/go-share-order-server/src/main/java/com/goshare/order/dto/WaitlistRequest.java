package com.goshare.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 候补登记/退出请求
 */
@Data
public class WaitlistRequest {

    @NotNull(message = "行程ID不能为空")
    private Long tripId;
}
