package com.goshare.gateway.filter;

import com.goshare.common.security.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 网关统一登录校验：白名单（登录/注册）放行，其余 /api 接口必须带有效 JWT
 * 校验通过后把用户ID透传给下游服务（X-User-Id 头）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;

    /** 无需登录的白名单（前缀匹配） */
    private static final List<String> WHITE_LIST = List.of(
            "/api/user/login",
            "/api/user/register",
            "/api/ping",
            "/actuator"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        // 非业务路径或白名单路径直接放行
        if (!path.startsWith("/api") || WHITE_LIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }
        // 校验 Bearer Token
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return reject(exchange, "未登录，请先登录");
        }
        try {
            Claims claims = jwtUtil.parseToken(auth.substring(7));
            String userId = claims.getSubject();
            // 透传用户ID给下游服务（业务服务仍会自行二次校验，双保险）
            ServerWebExchange mutated = exchange.mutate()
                    .request(r -> r.headers(h -> h.set("X-User-Id", userId)))
                    .build();
            return chain.filter(mutated);
        } catch (Exception e) {
            return reject(exchange, "登录已过期，请重新登录");
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().set("Content-Type", "application/json;charset=UTF-8");
        byte[] body = ("{\"code\":401,\"message\":\"" + message + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    /** 优先级最高（最先执行） */
    @Override
    public int getOrder() {
        return -100;
    }
}
