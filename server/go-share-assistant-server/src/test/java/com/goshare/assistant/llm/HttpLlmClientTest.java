package com.goshare.assistant.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * HTTP LLM 客户端：真实 API 调用解析 / 调用失败降级 mock 文案
 */
class HttpLlmClientTest {

    private LlmProperties properties;
    private RestTemplate restTemplate;
    private HttpLlmClient client;

    @BeforeEach
    void setUp() {
        properties = new LlmProperties();
        properties.setApiKey("sk-test");
        properties.setHttpUrl("http://llm:8080/v1/chat/completions");
        properties.setModel("deepseek-chat");
        properties.setModelLarge("qwen-max");
        restTemplate = mock(RestTemplate.class);
        client = new HttpLlmClient(properties, restTemplate, new ObjectMapper());
    }

    @Test
    void callParsesModelResponse() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"你好，我是AI助手\"}}],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":60}}";
        when(restTemplate.postForObject(eq("http://llm:8080/v1/chat/completions"), any(), eq(String.class)))
                .thenReturn(json);

        LlmClient.LlmResponse resp = client.call("前缀\n用户：你好", LlmClient.Tier.SMALL);

        assertEquals("你好，我是AI助手", resp.text());
        assertEquals(120, resp.inputTokens());
        assertEquals(60, resp.outputTokens());
        // 小模型档位映射 deepseek-chat
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(anyString(), entityCaptor.capture(), eq(String.class));
        assertTrue(entityCaptor.getValue().getBody().toString().contains("deepseek-chat"));
    }

    @Test
    void callFailureDegradesToMockText() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("llm down"));

        LlmClient.LlmResponse resp = client.call("前缀\n用户：你好", LlmClient.Tier.LARGE);

        assertTrue(resp.text().startsWith("抱歉，AI 服务暂时不可用"));
        assertEquals(0, resp.inputTokens());
    }
}
