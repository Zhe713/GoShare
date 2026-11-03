package com.goshare.assistant.llm;

import com.goshare.assistant.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP LLM 客户端（可插拔真实 API：Qwen/DeepSeek 等 OpenAI 兼容接口）
 * 本机演示默认走 Mock，此实现仅当配置 goshare.llm.mode=http 时启用
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "goshare.llm.mode", havingValue = "http")
public class HttpLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpLlmClient(LlmProperties properties,
                         @Qualifier("plainRestTemplate") RestTemplate restTemplate,
                         ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmResponse call(String prompt, Tier tier) {
        String model = tier == Tier.SMALL ? properties.getModel() : properties.getModelLarge();
        // 把系统前缀与用户问题拆成独立角色消息（避免 qwen 将 system 内“用户：…”误判为乱码）
        String system = prompt;
        String user = "请回答用户问题。";
        int idx = prompt.indexOf("\n用户：");
        if (idx > 0) {
            system = prompt.substring(0, idx);
            user = prompt.substring(idx + 4);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", new Object[]{
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)
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
            log.warn("[http-llm] 调用失败: {}", e.getMessage());
            return new LlmResponse("抱歉，AI 服务暂时不可用，请稍后再试。", 0, 0);
        }
    }
}
