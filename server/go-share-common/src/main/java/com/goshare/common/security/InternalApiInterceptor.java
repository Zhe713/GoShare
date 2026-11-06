package com.goshare.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 内部命令接口守卫：只放行带正确 {@code X-Internal-Token} 的调用。
 *
 * <p>三道防线中的第二道：
 * <ol>
 *   <li>网关把内部路径从公网路由摘掉（第一道）；</li>
 *   <li>本拦截器校验服务令牌（第二道，防止有人直连服务端口）；</li>
 *   <li>业务层校验数据归属与金额（第三道）。</li>
 * </ol>
 */
@Slf4j
public class InternalApiInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Internal-Token";

    private final String expectedToken;
    private final ObjectMapper objectMapper;

    public InternalApiInterceptor(String expectedToken, ObjectMapper objectMapper) {
        this.expectedToken = expectedToken;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        boolean internal = hm.hasMethodAnnotation(InternalApi.class)
                || hm.getBeanType().isAnnotationPresent(InternalApi.class);
        if (!internal) {
            return true;
        }
        String actual = request.getHeader(HEADER);
        if (expectedToken != null && expectedToken.equals(actual)) {
            return true;
        }
        log.warn("拒绝非内部调用 path={} remote={}", request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(403, "该接口仅限内部服务调用")));
        return false;
    }
}
