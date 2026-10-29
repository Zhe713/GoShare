package com.goshare.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * 网关限流配置：按客户端 IP 维度限流
 */
@Configuration
public class RateLimitConfig {

    /** 限流维度：客户端 IP */
    @Bean
    public KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress() == null
                        ? "unknown"
                        : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
        );
    }
}
