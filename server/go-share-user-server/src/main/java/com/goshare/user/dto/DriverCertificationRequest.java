package com.goshare.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 车主认证申请请求
 */
@Data
public class DriverCertificationRequest {

    @NotBlank(message = "车牌号不能为空")
    private String licensePlate;

    private String vehicleModel;

    @NotNull(message = "可拼座位数不能为空")
    @Min(value = 1, message = "座位数至少 1")
    @Max(value = 6, message = "座位数最多 6")
    private Integer seatCount;

    @NotBlank(message = "驾驶证号不能为空")
    private String drivingLicenseNo;
}
