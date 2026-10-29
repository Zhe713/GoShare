package com.goshare.common.security;

import com.goshare.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 从请求头 Authorization 解析当前登录用户（各服务共用）
 */
@Component
@RequiredArgsConstructor
public class CurrentUserResolver {

    private final JwtUtil jwtUtil;

    /** 解析并校验令牌，返回声明体 */
    public Claims resolveClaims(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException(401, "未登录，请先登录");
        }
        try {
            return jwtUtil.parseToken(authHeader.substring(7));
        } catch (Exception e) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
    }

    /** 当前登录用户ID */
    public Long resolveUserId(String authHeader) {
        return Long.valueOf(resolveClaims(authHeader).getSubject());
    }

    /** 当前登录用户角色 */
    public String resolveRole(String authHeader) {
        return resolveClaims(authHeader).get("role", String.class);
    }
}
