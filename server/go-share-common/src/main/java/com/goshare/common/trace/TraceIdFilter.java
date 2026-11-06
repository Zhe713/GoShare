package com.goshare.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 全链路 traceId 过滤器（阶段3「可观测性」）
 *
 * <p>每个请求进入时生成或透传 traceId，放入 MDC 与响应头 X-Trace-Id。
 * 日志通过 {@code %X{traceId}} 打印，一条业务链路（网关 → order → payment）可串联定位。
 *
 * <p>透传规则：优先取上游传的 X-Trace-Id（跨服务透传），否则生成新的。
 * 这样一次用户请求从网关到最底层服务共享同一个 traceId。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
