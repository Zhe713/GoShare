package com.goshare.assistant.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

/**
 * 通用 Bean 配置（服务间 HTTP 调用 / 可插拔 LLM 客户端）
 */
@Configuration
public class BeanConfig {

    /** 服务间 HTTP 调用客户端（走 Nacos 服务名 + 负载均衡；默认优先） */
    @Bean
    @LoadBalanced
    @Primary
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /** 外部 API 调用客户端（真实地址直连，不走负载均衡；供 LLM HTTP 客户端使用） */
    @Bean
    public RestTemplate plainRestTemplate() {
        return new RestTemplate();
    }
}
