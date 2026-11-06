package com.goshare.common.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 服务间调用客户端：自动带上内部令牌。
 *
 * <p>配合 {@link InternalApiInterceptor} 使用——服务端校验令牌，客户端注入令牌，
 * 业务代码不需要关心。这样"内部命令"和"用户 API"在传输层就分开了。
 */
public class InternalTokenClientInterceptor implements ClientHttpRequestInterceptor {

    private final String token;

    public InternalTokenClientInterceptor(String token) {
        this.token = token;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (token != null && !token.isBlank()) {
            request.getHeaders().set(InternalApiInterceptor.HEADER, token);
        }
        return execution.execute(request, body);
    }
}
