package com.goshare.marketing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 领券请求
 */
@Data
public class ReceiveRequest {

    /** 券模板ID */
    @NotNull(message = "模板ID不能为空")
    private Long templateId;
}
