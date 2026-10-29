package com.goshare.user.controller;

import com.goshare.common.result.Result;
import com.goshare.user.dto.LoginRequest;
import com.goshare.user.dto.LoginResponse;
import com.goshare.user.dto.RealNameVerifyRequest;
import com.goshare.user.dto.RegisterRequest;
import com.goshare.user.dto.UserVO;
import com.goshare.user.entity.User;
import com.goshare.user.service.UserService;
import com.goshare.common.security.CurrentUserResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口：注册、登录、个人信息、实名认证
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CurrentUserResolver currentUserResolver;

    @PostMapping("/register")
    public Result<LoginResponse> register(@RequestBody @Valid RegisterRequest request) {
        return Result.success(userService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return Result.success(userService.login(request));
    }

    @GetMapping("/me")
    public Result<UserVO> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        Long userId = currentUserResolver.resolveUserId(auth);
        User user = userService.getUserById(userId);
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        return Result.success(vo);
    }

    @PostMapping("/real-name-verify")
    public Result<Void> verifyRealName(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestBody @Valid RealNameVerifyRequest request) {
        Long userId = currentUserResolver.resolveUserId(auth);
        userService.verifyRealName(userId, request.getRealName(), request.getIdCardNo());
        return Result.success();
    }
}
