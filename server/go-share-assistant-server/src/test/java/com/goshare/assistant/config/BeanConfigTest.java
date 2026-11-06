package com.goshare.assistant.config;

import com.goshare.common.security.InternalTokenClientInterceptor;
import com.goshare.common.trace.TraceIdClientInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RestTemplate 超时配置：服务间调用与外部 LLM 调用均带 3s 连接 / 15s 读取超时（防外部不响应拖死主流程）
 */
class BeanConfigTest {

    @Test
    void restTemplatesCarryTimeouts() throws Exception {
        BeanConfig config = new BeanConfig();
        InternalTokenClientInterceptor internalToken = new InternalTokenClientInterceptor("test-token");
        TraceIdClientInterceptor traceId = new TraceIdClientInterceptor();
        assertTimeout(3000, 15000, config.restTemplate(internalToken, traceId).getRequestFactory());
        assertTimeout(3000, 15000, config.plainRestTemplate().getRequestFactory());
    }

    /** 服务间客户端必须带内部令牌拦截器，否则调 /api/order/internal、/api/user/{id} 会被 403 */
    @Test
    void internalRestTemplateCarriesTokenInterceptor() {
        BeanConfig config = new BeanConfig();
        InternalTokenClientInterceptor internalToken = new InternalTokenClientInterceptor("test-token");
        TraceIdClientInterceptor traceId = new TraceIdClientInterceptor();
        assertTrue(config.restTemplate(internalToken, traceId).getInterceptors().contains(internalToken));
        // 外部 LLM 客户端不应带内部令牌（避免把内部凭证发给第三方）
        assertTrue(config.plainRestTemplate().getInterceptors().isEmpty());
    }

    /** SimpleClientHttpRequestFactory 无 getter，反射读取超时字段验证 */
    private void assertTimeout(int connect, int read, Object factory) throws Exception {
        // RestTemplate 一旦加了拦截器，getRequestFactory() 返回的是 InterceptingClientHttpRequestFactory，
        // 需要下钻到真正的 delegate 才能读到超时
        if (factory instanceof InterceptingClientHttpRequestFactory intercepting) {
            factory = intercepting.getDelegate();
        }
        assertTrue(factory instanceof SimpleClientHttpRequestFactory, "实际类型: " + factory.getClass());
        Field connectField = SimpleClientHttpRequestFactory.class.getDeclaredField("connectTimeout");
        connectField.setAccessible(true);
        assertEquals(connect, connectField.getInt(factory));
        Field readField = SimpleClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        readField.setAccessible(true);
        assertEquals(read, readField.getInt(factory));
    }
}
