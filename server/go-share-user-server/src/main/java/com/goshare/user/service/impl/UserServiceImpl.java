package com.goshare.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.user.dto.LoginRequest;
import com.goshare.user.dto.LoginResponse;
import com.goshare.user.dto.RegisterRequest;
import com.goshare.user.entity.User;
import com.goshare.user.mapper.UserMapper;
import com.goshare.user.service.UserService;
import com.goshare.common.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public LoginResponse register(RegisterRequest request) {
        // 1. 手机号唯一性校验
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getPhone, request.getPhone()));
        if (count > 0) {
            throw new BusinessException(400, "该手机号已注册，请直接登录");
        }
        // 2. 落库（密码 BCrypt 加密，默认昵称）
        User user = new User();
        user.setPhone(request.getPhone());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() == null
                ? "乘客" + request.getPhone().substring(7) : request.getNickname());
        user.setRealNameVerified(0);
        user.setDriverVerified(0);
        user.setRole("PASSENGER");
        user.setStatus(1);
        userMapper.insert(user);
        // 3. 直接签发令牌
        return new LoginResponse(jwtUtil.createToken(user.getId(), user.getRole()),
                user.getId(), user.getNickname(), user.getRole());
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, request.getPhone()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "手机号或密码错误");
        }
        if (user.getStatus() == 0) {
            throw new BusinessException(403, "账号已被禁用，请联系管理员");
        }
        return new LoginResponse(jwtUtil.createToken(user.getId(), user.getRole()),
                user.getId(), user.getNickname(), user.getRole());
    }

    @Override
    public User getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return user;
    }

    @Override
    public void verifyRealName(Long userId, String realName, String idCardNo) {
        User user = getUserById(userId);
        user.setRealName(realName);
        user.setIdCardNo(idCardNo);
        user.setRealNameVerified(1);
        userMapper.updateById(user);
    }
}
