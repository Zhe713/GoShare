package com.goshare.common.config;

import com.goshare.common.trace.TraceIdClientInterceptor;
import com.goshare.common.trace.TraceIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 全链路 traceId 的公共装配（阶段3「可观测性」）。
 *
 * <p>与 {@link InternalApiAutoConfiguration} 同理，用自动装配而非 @Component 扫描，
 * 避免 WebFlux 网关启动失败。注册 {@link TraceIdFilter}（servlet 过滤器，最高优先级），
 * 供各服务的 RestTemplate 透传 traceId 的 {@link TraceIdClientInterceptor} 由各服务
 * BeanConfig 自行加入 RestTemplate（因为 RestTemplate 是各服务自己 new 的）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TraceAutoConfiguration {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(TraceIdFilter filter) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public TraceIdClientInterceptor traceIdClientInterceptor() {
        return new TraceIdClientInterceptor();
    }
}
