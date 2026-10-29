package com.goshare.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录/注册响应（含令牌）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    /** 登录令牌 */
    private String token;

    /** 用户ID */
    private Long userId;

    /** 昵称 */
    private String nickname;

    /** 角色 */
    private String role;
}
