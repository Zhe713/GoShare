package com.goshare.user.controller;

import com.goshare.common.result.Result;
import com.goshare.common.security.InternalApi;
import com.goshare.user.dto.LoginRequest;
import com.goshare.user.dto.LoginResponse;
import com.goshare.user.dto.RealNameVerifyRequest;
import com.goshare.user.dto.RegisterRequest;
import com.goshare.user.dto.UserProfileVO;
import com.goshare.user.dto.UserVO;
import com.goshare.user.entity.User;
import com.goshare.user.service.UserService;
import com.goshare.common.security.CurrentUserResolver;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 他人可见的公开资料（脱敏）。
     *
     * <p>行程详情页展示车主卡片用：昵称/头像/信任分/实名与车主认证状态，
     * 不含手机号、真名、身份证。任何登录用户都可查，因为这是"上车前必须知道的信息"。
     */
    @GetMapping("/{userId}/profile")
    public Result<UserProfileVO> profile(@PathVariable("userId") Long userId) {
        User user = userService.getUserById(userId);
        if (user == null) {
            throw new com.goshare.common.exception.BusinessException(404, "用户不存在");
        }
        UserProfileVO vo = new UserProfileVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setTrustScore(user.getTrustScore());
        vo.setRealNameVerified(user.getRealNameVerified() != null && user.getRealNameVerified() == 1);
        vo.setDriverVerified(user.getDriverVerified() != null && user.getDriverVerified() == 1);
        vo.setJoinedAt(user.getCreatedAt());
        return Result.success(vo);
    }

    /**
     * 按用户ID查询（供出行助手撮合排序取信任分等特征）。
     *
     * <p>标 {@link InternalApi}：返回体里有手机号和真名，之前任何登录用户遍历 ID
     * 就能批量拉走全站用户的手机号（实测可复现）。公开信息请走 {@code /{userId}/profile}。
     */
    @InternalApi
    @GetMapping("/{userId}")
    public Result<UserVO> getById(@PathVariable("userId") Long userId) {
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
