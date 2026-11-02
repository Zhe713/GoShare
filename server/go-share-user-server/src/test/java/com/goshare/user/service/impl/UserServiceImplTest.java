package com.goshare.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.goshare.common.exception.BusinessException;
import com.goshare.common.security.JwtUtil;
import com.goshare.user.dto.LoginRequest;
import com.goshare.user.dto.LoginResponse;
import com.goshare.user.dto.RegisterRequest;
import com.goshare.user.entity.User;
import com.goshare.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 用户服务：注册唯一性/加密落库、登录鉴权、禁用拦截、实名认证
 */
class UserServiceImplTest {

    private UserMapper userMapper;
    private BCryptPasswordEncoder encoder;
    private JwtUtil jwtUtil;
    private UserServiceImpl service;

    private static final String SECRET = "goshare-jwt-secret-key-0123456789abcdefghij";

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        encoder = new BCryptPasswordEncoder();
        jwtUtil = new JwtUtil(SECRET, 1L);
        service = new UserServiceImpl(userMapper, encoder, jwtUtil);
    }

    @Test
    void registerRejectsDuplicatePhone() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        RegisterRequest req = new RegisterRequest();
        req.setPhone("13900139000");
        req.setPassword("123456");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.register(req));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已注册"));
    }

    @Test
    void registerSuccessWithEncryptedPassword() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        RegisterRequest req = new RegisterRequest();
        req.setPhone("13900139000");
        req.setPassword("123456");
        req.setNickname("测试乘客");
        LoginResponse resp = service.register(req);
        assertNotNull(resp.getToken());
        assertEquals("PASSENGER", resp.getRole());
        assertEquals("测试乘客", resp.getNickname());
        // 落库密码为 BCrypt 密文且能匹配
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertTrue(encoder.matches("123456", captor.getValue().getPasswordHash()));
        // 签发 token 可解析出用户 ID 与角色
        assertEquals(String.valueOf(resp.getUserId()),
                jwtUtil.parseToken(resp.getToken()).getSubject());
    }

    @Test
    void registerDefaultNicknameFromPhone() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        RegisterRequest req = new RegisterRequest();
        req.setPhone("13900139000");
        req.setPassword("123456");
        LoginResponse resp = service.register(req);
        assertEquals("乘客9000", resp.getNickname());
    }

    @Test
    void loginWrongPasswordRejected() {
        User u = new User();
        u.setId(2L);
        u.setPhone("13900139000");
        u.setPasswordHash(encoder.encode("654321"));
        u.setNickname("乘客");
        u.setStatus(1);
        u.setRole("PASSENGER");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(u);
        LoginRequest req = new LoginRequest();
        req.setPhone("13900139000");
        req.setPassword("123456");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.login(req));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("手机号或密码错误"));
    }

    @Test
    void loginUserNotFoundRejected() {
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        LoginRequest req = new LoginRequest();
        req.setPhone("13900139000");
        req.setPassword("123456");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.login(req));
        assertEquals(400, ex.getCode());
    }

    @Test
    void loginDisabledUserRejected() {
        User u = new User();
        u.setId(2L);
        u.setPhone("13900139000");
        u.setPasswordHash(encoder.encode("123456"));
        u.setStatus(0);
        u.setRole("PASSENGER");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(u);
        LoginRequest req = new LoginRequest();
        req.setPhone("13900139000");
        req.setPassword("123456");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.login(req));
        assertEquals(403, ex.getCode());
    }

    @Test
    void loginSuccess() {
        User u = new User();
        u.setId(2L);
        u.setPhone("13900139000");
        u.setPasswordHash(encoder.encode("123456"));
        u.setNickname("乘客甲");
        u.setStatus(1);
        u.setRole("PASSENGER");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(u);
        LoginRequest req = new LoginRequest();
        req.setPhone("13900139000");
        req.setPassword("123456");
        LoginResponse resp = service.login(req);
        assertEquals("乘客甲", resp.getNickname());
        assertEquals("2", jwtUtil.parseToken(resp.getToken()).getSubject());
    }

    @Test
    void getUserByIdNotFound() {
        when(userMapper.selectById(99L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.getUserById(99L));
        assertEquals(404, ex.getCode());
    }

    @Test
    void verifyRealNameUpdatesUser() {
        User u = new User();
        u.setId(2L);
        u.setPhone("13900139000");
        u.setNickname("乘客");
        when(userMapper.selectById(2L)).thenReturn(u);
        service.verifyRealName(2L, "孙哲", "130101199901011234");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals("孙哲", captor.getValue().getRealName());
        assertEquals(1, captor.getValue().getRealNameVerified());
    }
}
