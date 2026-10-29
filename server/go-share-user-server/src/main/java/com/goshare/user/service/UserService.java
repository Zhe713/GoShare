package com.goshare.user.service;

import com.goshare.user.dto.LoginRequest;
import com.goshare.user.dto.LoginResponse;
import com.goshare.user.dto.RegisterRequest;
import com.goshare.user.entity.User;

/**
 * 用户服务：注册、登录、实名认证
 */
public interface UserService {

    /** 注册并直接返回登录态 */
    LoginResponse register(RegisterRequest request);

    /** 登录 */
    LoginResponse login(LoginRequest request);

    /** 按 ID 查询用户 */
    User getUserById(Long userId);

    /** 实名认证 */
    void verifyRealName(Long userId, String realName, String idCardNo);
}
