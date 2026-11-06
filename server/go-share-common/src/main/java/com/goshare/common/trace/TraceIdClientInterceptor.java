package com.goshare.common.trace;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 服务间调用客户端：透传当前请求的 traceId。
 *
 * <p>配合 {@link TraceIdFilter} 使用——本服务收到请求时把 traceId 放入 MDC，
 * 发起下游 HTTP 调用时把 MDC 里的 traceId 写进请求头，下游服务沿用同一个 traceId。
 * 这样一条业务链路的日志能被同一个 traceId 串联。
 */
public class TraceIdClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().set(TraceIdFilter.TRACE_HEADER, traceId);
        }
        return execution.execute(request, body);
    }
}
