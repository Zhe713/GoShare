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
}
