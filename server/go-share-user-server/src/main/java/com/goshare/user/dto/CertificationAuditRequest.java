package com.goshare.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 车主认证审核请求（管理端）
 */
@Data
public class CertificationAuditRequest {

    @NotNull(message = "认证记录ID不能为空")
    private Long certificationId;

    /** 是否通过 */
    @NotNull(message = "审核结论不能为空")
    private Boolean pass;

    /** 审核备注 */
    private String remark;
}
