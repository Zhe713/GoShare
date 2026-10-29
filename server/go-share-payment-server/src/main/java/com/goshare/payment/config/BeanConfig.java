package com.goshare.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 支付服务通用 Bean 配置
 */
@Configuration
public class BeanConfig {

    /** 服务间 HTTP 调用客户端 */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
