package com.goshare.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 内部命令接口在网关层直接屏蔽。
 *
 * <p>背景：这些接口本来是服务之间的命令（支付回调、券核销/退回、认证审核、内部订单查询），
 * 却和用户 API 挂在同一个路由下。实测中，任何登录用户拿自己的 JWT 就能：
 * <ul>
 *   <li>POST /api/order/pay-callback → 把订单改成已支付（不付钱）；</li>
 *   <li>POST /api/coupon/return → 把锁定的券退回来，再用同一张券下第二单；</li>
 *   <li>POST /api/driver/certification/audit → 自己批自己的车主认证。</li>
 * </ul>
 *
 * <p>这里做第一道拦截：公网请求一旦命中这些路径直接 404（不返回 403，避免暴露接口存在）。
 * 第二道是服务侧的 {@code InternalApiInterceptor} 校验内部令牌——两道都过不去才算安全。
 *
 * <p>注意：服务之间通过 Nacos 服务名直连，不经过网关，所以不受影响。
 */
@Slf4j
@Component
public class InternalRouteBlockFilter implements GlobalFilter, Ordered {

    /** 内部命令路径（前缀匹配） */
    private static final List<String> INTERNAL_PATHS = List.of(
            "/api/order/pay-callback",
            "/api/order/internal/",
            "/api/coupon/settle",
            "/api/coupon/confirm",
            "/api/coupon/return",
            "/api/driver/certification/audit"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        boolean internal = INTERNAL_PATHS.stream().anyMatch(path::startsWith);
        if (!internal) {
            return chain.filter(exchange);
        }
        log.warn("网关拦截内部命令的对外访问 path={} ip={}", path,
                exchange.getRequest().getRemoteAddress());
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
        exchange.getResponse().getHeaders().set("Content-Type", "application/json;charset=UTF-8");
        byte[] body = "{\"code\":404,\"message\":\"请求的接口不存在\"}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    /** 比鉴权过滤器更早执行，未登录也拿不到任何信息 */
    @Override
    public int getOrder() {
        return -200;
    }
}
