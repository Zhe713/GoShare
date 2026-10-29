package com.goshare.marketing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建券模板请求（管理端）
 */
@Data
public class TemplateCreateRequest {

    @NotBlank(message = "券名称不能为空")
    private String templateName;

    /** FULL_REDUCTION满减 DISCOUNT折扣 CASH立减 */
    @NotBlank(message = "券类型不能为空")
    private String type;

    /** 满X元可用（0=无门槛） */
    @NotNull(message = "门槛金额不能为空")
    @DecimalMin(value = "0.00", message = "门槛不能为负")
    private BigDecimal threshold;

    /** 满减/立减金额（元），折扣券为折扣率（如85=8.5折） */
    @NotNull(message = "券面值不能为空")
    @DecimalMin(value = "0.01", message = "券面值必须大于 0")
    private BigDecimal discountValue;

    /** 发行总量（0=不限量） */
    @NotNull(message = "发行总量不能为空")
    @Min(value = 0, message = "发行总量不能为负")
    private Integer totalCount;

    @NotNull(message = "领取开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "领取结束时间不能为空")
    private LocalDateTime endTime;

    /** 领取后有效期（天），默认 7 */
    private Integer expireDays;

    private String remark;
}
