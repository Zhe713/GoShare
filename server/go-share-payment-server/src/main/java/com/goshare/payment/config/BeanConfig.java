package com.goshare.payment.config;

import com.goshare.common.security.InternalTokenClientInterceptor;
import com.goshare.common.trace.TraceIdClientInterceptor;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 支付服务通用 Bean 配置
 */
@Configuration
public class BeanConfig {

    /** 服务间 HTTP 调用客户端：带内部令牌（回调订单服务需要）+ traceId 透传 + 有限超时 */
    @Bean
    @LoadBalanced
    public RestTemplate restTemplate(InternalTokenClientInterceptor internalTokenClientInterceptor,
                                     TraceIdClientInterceptor traceIdClientInterceptor) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.getInterceptors().add(internalTokenClientInterceptor);
        restTemplate.getInterceptors().add(traceIdClientInterceptor);
        return restTemplate;
    }
}
