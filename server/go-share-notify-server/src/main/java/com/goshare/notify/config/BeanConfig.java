package com.goshare.notify.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

/**
 * 通知服务通用 Bean 配置
 */
@Configuration
public class BeanConfig {

    /** 服务间 HTTP 调用客户端（走 Nacos 服务名 + 负载均衡；默认首选，供无 @Qualifier 的注入方使用） */
    @Bean
    @Primary
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /** 外部公网 API 客户端（不走负载均衡；天气真实数据源 Open-Meteo 等外部域名专用） */
    @Bean
    @Qualifier("plainRestTemplate")
    public RestTemplate plainRestTemplate() {
        return new RestTemplate();
    }
}
