package com.goshare.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 车主认证实体（对应表 driver_certification）
 */
@Data
@TableName("driver_certification")
public class DriverCertification {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private Long userId;

    /** 车牌号 */
    private String licensePlate;

    /** 车型 */
    private String vehicleModel;

    /** 可拼座位数 */
    private Integer seatCount;

    /** 驾驶证号 */
    private String drivingLicenseNo;

    /** 审核状态 0待审核 1通过 2拒绝 */
    private Integer status;

    /** 审核备注 */
    private String auditRemark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
