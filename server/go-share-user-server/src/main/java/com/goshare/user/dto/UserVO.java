package com.goshare.user.dto;

import lombok.Data;

/**
 * 当前用户信息（返回给前端）
 */
@Data
public class UserVO {

    private Long id;

    private String phone;

    private String nickname;

    private String avatarUrl;

    private String realName;

    private Integer realNameVerified;

    private Integer driverVerified;

    private String role;

    /** 车主信任分 0-5（撮合排序特征） */
    private java.math.BigDecimal trustScore;
}
