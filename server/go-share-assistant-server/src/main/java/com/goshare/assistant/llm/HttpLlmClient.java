package com.goshare.assistant.llm;

import com.goshare.assistant.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP LLM 客户端（可插拔真实 API：DeepSeek 等 OpenAI 兼容接口）
 * 本机演示默认走 Mock，此实现仅当配置 goshare.llm.mode=http 时启用
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "goshare.llm.mode", havingValue = "http")
public class HttpLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public LlmResponse call(String prompt, Tier tier) {
        String model = tier == Tier.SMALL ? "deepseek-chat" : "deepseek-reasoner";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", new Object[]{
                        Map.of("role", "system", "content", prompt),
                        Map.of("role", "user", "content", "请生成")
                }
        );
        try {
            long start = System.currentTimeMillis();
            String resp = restTemplate.postForObject(
                    properties.getHttpUrl(),
                    new HttpEntity<>(body, headers),
                    String.class);
            JsonNode node = objectMapper.readTree(resp);
            String text = node.path("choices").path(0).path("message").path("content").asText();
            int in = node.path("usage").path("prompt_tokens").asInt(0);
            int out = node.path("usage").path("completion_tokens").asInt(0);
            return new LlmResponse(text, in, out);
        } catch (Exception e) {
            log.warn("[http-llm] 调用失败，降级为 mock 文案: {}", e.getMessage());
            return new LlmResponse("[模型服务不可用]" + prompt, 0, 0);
        }
    }
}
