package com.goshare.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.common.security.InternalApiInterceptor;
import com.goshare.common.security.InternalTokenClientInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 内部命令接口的公共装配。
 *
 * <p>用自动装配而不是 @Component 扫描：gateway 是 WebFlux 应用，没有 servlet API，
 * 扫描到这里会启动失败（历史上踩过 NoClassDefFoundError: jakarta/servlet）。
 *
 * <p>令牌走配置 {@code goshare.internal.token}，各服务保持一致；生产环境应换成密钥管理下发的随机值。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class InternalApiAutoConfiguration {

    @Bean
    public InternalApiInterceptor internalApiInterceptor(
            @Value("${goshare.internal.token:goshare-internal-dev-token}") String token,
            ObjectMapper objectMapper) {
        return new InternalApiInterceptor(token, objectMapper);
    }

    @Bean
    public InternalTokenClientInterceptor internalTokenClientInterceptor(
            @Value("${goshare.internal.token:goshare-internal-dev-token}") String token) {
        return new InternalTokenClientInterceptor(token);
    }

    @Bean
    public WebMvcConfigurer internalApiWebMvcConfigurer(InternalApiInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/api/**");
            }
        };
    }
}
