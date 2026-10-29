package com.goshare.user.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 车主认证信息（返回给前端）
 */
@Data
public class DriverCertificationVO {

    private Long id;

    private String licensePlate;

    private String vehicleModel;

    private Integer seatCount;

    private String drivingLicenseNo;

    /** 0待审核 1通过 2拒绝 */
    private Integer status;

    private String auditRemark;

    private LocalDateTime createdAt;
}
