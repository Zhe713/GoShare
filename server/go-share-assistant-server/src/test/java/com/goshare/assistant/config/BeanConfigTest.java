package com.goshare.assistant.config;

import org.junit.jupiter.api.Test;
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
        assertTimeout(3000, 15000, config.restTemplate().getRequestFactory());
        assertTimeout(3000, 15000, config.plainRestTemplate().getRequestFactory());
    }

    /** SimpleClientHttpRequestFactory 无 getter，反射读取超时字段验证 */
    private void assertTimeout(int connect, int read, Object factory) throws Exception {
        assertTrue(factory instanceof SimpleClientHttpRequestFactory);
        Field connectField = SimpleClientHttpRequestFactory.class.getDeclaredField("connectTimeout");
        connectField.setAccessible(true);
        assertEquals(connect, connectField.getInt(factory));
        Field readField = SimpleClientHttpRequestFactory.class.getDeclaredField("readTimeout");
        readField.setAccessible(true);
        assertEquals(read, readField.getInt(factory));
    }
}
