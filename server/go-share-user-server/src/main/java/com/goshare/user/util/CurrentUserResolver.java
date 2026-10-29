package com.goshare.user.util;

import com.goshare.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 从请求头 Authorization 解析当前登录用户ID
 */
@Component
@RequiredArgsConstructor
public class CurrentUserResolver {

    private final JwtUtil jwtUtil;

    public Long resolveUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException(401, "未登录，请先登录");
        }
        try {
            Claims claims = jwtUtil.parseToken(authHeader.substring(7));
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
    }
}
