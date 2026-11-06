package com.goshare.order.config;

import com.goshare.common.security.InternalTokenClientInterceptor;
import com.goshare.common.trace.TraceIdClientInterceptor;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 订单服务通用 Bean 配置
 */
@Configuration
public class BeanConfig {

    /**
     * 服务间 HTTP 调用客户端（走 Nacos 服务名 + 负载均衡）。
     *
     * <p>两处加固：
     * <ul>
     *   <li>注入内部令牌：调用券服务的 settle/confirm/return、支付服务的流水查询时自动带上
     *       {@code X-Internal-Token}，否则会被内部命令守卫拦下；</li>
     *   <li>设置超时：原来 {@code new RestTemplate()} 的默认超时是无限的，一个卡住的券服务
     *       能把订单服务的线程和数据库连接一起耗光。</li>
     * </ul>
     */
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
