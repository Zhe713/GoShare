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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP LLM 客户端（可插拔真实 API：Qwen 等 OpenAI 兼容接口）
 * 本机演示默认走 Mock，此实现仅当配置 goshare.llm.mode=http 时启用
 * <p>
 * 支持两种通道：
 * - call：单轮文本生成（系统前缀 + 用户问题）
 * - chat：Function Calling 工具通道（多角色消息 + 工具描述 + tool_calls 解析）
 * 调用失败自动重试 1 次；仍失败则返回降级文案（绝不把异常当答案）。
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
        // 用最后一次「用户：」切分：历史上下文留在 system，最新问题作为 user 消息
        String system = prompt;
        String user = "请回答用户问题。";
        int idx = prompt.lastIndexOf("\n用户：");
        if (idx > 0) {
            system = prompt.substring(0, idx);
            user = prompt.substring(idx + 4);
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", system, null, null));
        messages.add(message("user", user, null, null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        ToolChatResponse resp = postChat(body, model, true);
        return new LlmResponse(resp.text(), resp.inputTokens(), resp.outputTokens());
    }

    @Override
    public ToolChatResponse chat(List<ToolMessage> messages, List<Map<String, Object>> tools, Tier tier) {
        String model = tier == Tier.SMALL ? properties.getModel() : properties.getModelLarge();
        List<Map<String, Object>> wire = new ArrayList<>();
        for (ToolMessage m : messages) {
            wire.add(message(m.role(), m.content(), m.toolCalls(), m.toolCallId()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", wire);
        body.put("tools", tools);
        return postChat(body, model, false);
    }

    /** 统一 POST 并解析（true=单轮文本 / false=工具通道）；失败重试一次后降级 */
    private ToolChatResponse postChat(Map<String, Object> body, String model, boolean textOnly) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                long start = System.currentTimeMillis();
                String resp = restTemplate.postForObject(
                        properties.getHttpUrl(),
                        new HttpEntity<>(body, headers),
                        String.class);
                JsonNode node = objectMapper.readTree(resp);
                JsonNode msg = node.path("choices").path(0).path("message");
                String text = msg.path("content").isNull() ? null : msg.path("content").asText();
                List<ToolCall> calls = new ArrayList<>();
                JsonNode callsNode = msg.path("tool_calls");
                if (callsNode.isArray()) {
                    for (JsonNode c : callsNode) {
                        calls.add(new ToolCall(
                                c.path("id").asText(""),
                                c.path("function").path("name").asText(""),
                                c.path("function").path("arguments").asText("{}")));
                    }
                }
                int in = node.path("usage").path("prompt_tokens").asInt(0);
                int out = node.path("usage").path("completion_tokens").asInt(0);
                log.info("[http-llm] 调用成功 model={} in={} out={} tools={} ms={}",
                        model, in, out, calls.size(), System.currentTimeMillis() - start);
                return new ToolChatResponse(text == null ? "" : text, calls, in, out);
            } catch (Exception e) {
                last = e;
                log.warn("[http-llm] 第{}次调用失败 model={} err={}", attempt, model, e.getMessage());
            }
        }
        String fallback = textOnly
                ? "抱歉，AI 服务暂时不可用，请稍后再试。"
                : "抱歉，AI 服务暂时不可用，我无法查询这项数据，请稍后再试。";
        return new ToolChatResponse(fallback, 0, 0);
    }

    /** 消息序列化（content 可为 null：assistant 携带 tool_calls 时 content 为空） */
    private Map<String, Object> message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        if (content != null) {
            m.put("content", content);
        } else {
            m.put("content", null);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<Map<String, Object>> wire = new ArrayList<>();
            for (ToolCall c : toolCalls) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", c.name());
                fn.put("arguments", c.arguments());
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", c.id());
                call.put("type", "function");
                call.put("function", fn);
                wire.add(call);
            }
            m.put("tool_calls", wire);
        }
        if (toolCallId != null) {
            m.put("tool_call_id", toolCallId);
        }
        return m;
    }
}
