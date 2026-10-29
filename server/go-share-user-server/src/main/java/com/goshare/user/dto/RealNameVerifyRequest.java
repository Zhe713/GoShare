package com.goshare.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 实名认证请求
 */
@Data
public class RealNameVerifyRequest {

    @NotBlank(message = "真实姓名不能为空")
    private String realName;

    @NotBlank(message = "身份证号不能为空")
    private String idCardNo;
}
