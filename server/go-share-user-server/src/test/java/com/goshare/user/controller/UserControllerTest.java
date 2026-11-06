package com.goshare.user.controller;

import com.goshare.common.exception.BusinessException;
import com.goshare.common.result.Result;
import com.goshare.common.security.CurrentUserResolver;
import com.goshare.user.dto.LoginRequest;
import com.goshare.user.dto.LoginResponse;
import com.goshare.user.dto.RealNameVerifyRequest;
import com.goshare.user.dto.RegisterRequest;
import com.goshare.user.dto.UserProfileVO;
import com.goshare.user.dto.UserVO;
import com.goshare.user.entity.User;
import com.goshare.user.service.UserService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserController 单测：注册/登录/当前用户/脱敏公开资料/内部接口/实名认证。
 *
 * <p>重点覆盖 {@code /{userId}/profile}：行程详情页的车主卡片靠它展示信任信息，
 * 且必须**只暴露脱敏字段**（昵称/头像/信任分/认证状态），不能带手机号与真名。
 */
class UserControllerTest {

    private final UserService userService = mock(UserService.class);
    private final CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    private final UserController controller = new UserController(userService, resolver);

    private User sampleUser() {
        User u = new User();
        u.setId(9L);
        u.setPhone("13700137000");
        u.setRealName("张三");
        u.setNickname("小张");
        u.setAvatarUrl("https://cdn.example.com/a.png");
        u.setRealNameVerified(1);
        u.setDriverVerified(1);
        u.setTrustScore(new BigDecimal("4.80"));
        u.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        return u;
    }

    @Test
    void registerDelegatesToService() {
        LoginResponse resp = new LoginResponse();
        when(userService.register(any(RegisterRequest.class))).thenReturn(resp);

        RegisterRequest req = new RegisterRequest();
        req.setPhone("13700137000");
        req.setPassword("123456");
        Result<LoginResponse> result = controller.register(req);

        assertEquals(200, result.getCode());
        assertEquals(resp, result.getData());
    }

    @Test
    void loginDelegatesToService() {
        LoginResponse resp = new LoginResponse();
        when(userService.login(any(LoginRequest.class))).thenReturn(resp);

        LoginRequest req = new LoginRequest();
        req.setPhone("13700137000");
        req.setPassword("123456");
        Result<LoginResponse> result = controller.login(req);

        assertEquals(200, result.getCode());
        assertEquals(resp, result.getData());
    }

    @Test
    void meReturnsCurrentUserVo() {
        when(resolver.resolveUserId("Bearer t")).thenReturn(9L);
        when(userService.getUserById(9L)).thenReturn(sampleUser());

        Result<UserVO> result = controller.me("Bearer t");

        assertEquals(9L, result.getData().getId());
        assertEquals("小张", result.getData().getNickname());
    }

    @Test
    void profileExposesOnlyMaskedFields() {
        when(userService.getUserById(9L)).thenReturn(sampleUser());

        Result<UserProfileVO> result = controller.profile(9L);
        UserProfileVO vo = result.getData();

        assertEquals(9L, vo.getId());
        assertEquals("小张", vo.getNickname());
        assertEquals(new BigDecimal("4.80"), vo.getTrustScore());
        assertTrue(vo.getRealNameVerified());
        assertTrue(vo.getDriverVerified());
        // 脱敏：VO 中不存在手机号/真名/身份证字段（用反射断言，防止以后有人把它们加回来）
        assertThrows(NoSuchFieldException.class, () -> UserProfileVO.class.getDeclaredField("phone"));
        assertThrows(NoSuchFieldException.class, () -> UserProfileVO.class.getDeclaredField("realName"));
        assertThrows(NoSuchFieldException.class, () -> UserProfileVO.class.getDeclaredField("idCardNo"));
    }

    @Test
    void profileFlagsFalseWhenNotVerified() {
        User u = sampleUser();
        u.setRealNameVerified(null);
        u.setDriverVerified(0);
        when(userService.getUserById(9L)).thenReturn(u);

        UserProfileVO vo = controller.profile(9L).getData();

        assertFalse(vo.getRealNameVerified());
        assertFalse(vo.getDriverVerified());
    }

    @Test
    void profileThrows404WhenUserMissing() {
        when(userService.getUserById(anyLong())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.profile(404L));

        assertEquals(404, ex.getCode());
        assertTrue(ex.getMessage().contains("用户不存在"));
    }

    @Test
    void getByIdCopiesUserForInternalCall() {
        when(userService.getUserById(9L)).thenReturn(sampleUser());

        Result<UserVO> result = controller.getById(9L);

        assertEquals(9L, result.getData().getId());
    }

    @Test
    void verifyRealNameDelegatesWithResolvedUser() {
        when(resolver.resolveUserId(anyString())).thenReturn(9L);
        RealNameVerifyRequest req = new RealNameVerifyRequest();
        req.setRealName("张三");
        req.setIdCardNo("110101199001010011");

        Result<Void> result = controller.verifyRealName("Bearer t", req);

        assertEquals(200, result.getCode());
        verify(userService).verifyRealName(9L, "张三", "110101199001010011");
    }
}
