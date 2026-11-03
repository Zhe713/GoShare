package com.goshare.assistant.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

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
        // 失败后自动重试 1 次（共 2 次尝试）
        verify(restTemplate, times(2)).postForObject(anyString(), any(), eq(String.class));
    }

    @Test
    void callRetriesOnceThenSucceeds() {
        when(restTemplate.postForObject(eq("http://llm:8080/v1/chat/completions"), any(), eq(String.class)))
                .thenThrow(new RuntimeException("llm down 1"))
                .thenReturn("{\"choices\":[{\"message\":{\"content\":\"恢复成功\"}}],"
                        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}");

        LlmClient.LlmResponse resp = client.call("前缀\n用户：你好", LlmClient.Tier.SMALL);

        assertEquals("恢复成功", resp.text());
        assertEquals(5, resp.inputTokens());
        verify(restTemplate, times(2)).postForObject(anyString(), any(), eq(String.class));
    }

    @Test
    void chatParsesToolCalls() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_lunar_date\",\"arguments\":\"{\\\"date\\\":\\\"2026-11-03\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":10}}";
        when(restTemplate.postForObject(eq("http://llm:8080/v1/chat/completions"), any(), eq(String.class)))
                .thenReturn(json);

        List<LlmClient.ToolMessage> msgs = List.of(
                new LlmClient.ToolMessage("system", "s"),
                new LlmClient.ToolMessage("user", "农历"));
        LlmClient.ToolChatResponse resp = client.chat(msgs, List.of(Map.of("type", "function")), LlmClient.Tier.SMALL);

        assertEquals(1, resp.toolCalls().size());
        assertEquals("call_1", resp.toolCalls().get(0).id());
        assertEquals("get_lunar_date", resp.toolCalls().get(0).name());
        assertTrue(resp.toolCalls().get(0).arguments().contains("2026-11-03"));
        assertEquals(30, resp.inputTokens());
        assertEquals(10, resp.outputTokens());
    }

    @Test
    void chatWithoutToolCallsReturnsPlainText() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"正常回答\"}}],"
                + "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}";
        when(restTemplate.postForObject(anyString(), any(), eq(String.class))).thenReturn(json);

        LlmClient.ToolChatResponse resp = client.chat(
                List.of(new LlmClient.ToolMessage("user", "你好")), List.of(), LlmClient.Tier.SMALL);

        assertEquals("正常回答", resp.text());
        assertTrue(resp.toolCalls().isEmpty());
    }

    @Test
    void chatFailureDegradesToToolFallback() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("llm down"));

        LlmClient.ToolChatResponse resp = client.chat(
                List.of(new LlmClient.ToolMessage("user", "农历")), List.of(), LlmClient.Tier.SMALL);

        assertTrue(resp.text().contains("暂时不可用"));
        assertTrue(resp.toolCalls().isEmpty());
        verify(restTemplate, times(2)).postForObject(anyString(), any(), eq(String.class));
    }

    @Test
    void chatRetriesOnceThenSucceeds() {
        when(restTemplate.postForObject(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("down"))
                .thenReturn("{\"choices\":[{\"message\":{\"content\":\"农历丙午（马）年九月廿五\"}}],"
                        + "\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":4}}");

        LlmClient.ToolChatResponse resp = client.chat(
                List.of(new LlmClient.ToolMessage("user", "农历")), List.of(), LlmClient.Tier.SMALL);

        assertEquals("农历丙午（马）年九月廿五", resp.text());
        verify(restTemplate, times(2)).postForObject(anyString(), any(), eq(String.class));
    }
}
