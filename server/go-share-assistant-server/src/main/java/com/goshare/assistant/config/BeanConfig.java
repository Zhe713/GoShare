package com.goshare.assistant.config;

import com.goshare.common.security.InternalTokenClientInterceptor;
import com.goshare.common.trace.TraceIdClientInterceptor;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 通用 Bean 配置（服务间 HTTP 调用 / 可插拔 LLM 客户端）
 */
@Configuration
public class BeanConfig {

    /** 服务间 HTTP 调用客户端（走 Nacos 服务名 + 负载均衡；默认优先），带超时防挂死 + 内部令牌 + traceId 透传 */
    @Bean
    @LoadBalanced
    @Primary
    public RestTemplate restTemplate(InternalTokenClientInterceptor internalTokenClientInterceptor,
                                     TraceIdClientInterceptor traceIdClientInterceptor) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(15000);
        RestTemplate restTemplate = new RestTemplate(factory);
        // 助手要调用户服务的 /api/user/{id} 取信任分，该接口已收为内部命令
        restTemplate.getInterceptors().add(internalTokenClientInterceptor);
        restTemplate.getInterceptors().add(traceIdClientInterceptor);
        return restTemplate;
    }

    /** 外部 API 调用客户端（真实地址直连，不走负载均衡；供 LLM HTTP 客户端使用），带超时+由调用方重试 */
    @Bean
    public RestTemplate plainRestTemplate() {
        return restTemplateWithTimeout();
    }

    /** 统一超时：连接 3s / 读取 15s（LLM 生成慢，读取放宽；防止外部服务不响应拖死主流程） */
    private RestTemplate restTemplateWithTimeout() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(15000);
        return new RestTemplate(factory);
    }
}
