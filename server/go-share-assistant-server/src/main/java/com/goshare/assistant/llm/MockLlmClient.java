package com.goshare.assistant.llm;

import com.goshare.assistant.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Mock LLM 客户端（默认）：零成本可演示
 * 规则：
 * 1. 输出为模板化话术——金额、行程列表等业务数据由调用方（代码）注入，mock 只做文案组装；
 * 2. token 数按中文字数模拟（约 1 字 ≈ 1.3 token），输入/输出分开计，供记账表展示成本节省效果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "goshare.llm.mode", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    private final LlmProperties properties;

    @Override
    public LlmResponse call(String prompt, Tier tier) {
        // 模拟模型处理耗时（大模型更久）
        int delay = tier == Tier.LARGE ? 30 : 5;
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String body = extractUserBody(prompt);
        // mock 输出：把"请生成"类请求转成占位模板，真正内容由业务代码替换（见各业务组装处）
        String text = switch (tier) {
            case SMALL -> "【小模型·规则答案】" + body;
            case LARGE -> "【大模型·mock 输出】" + body;
        };
        int inputTokens = estimateTokens(prompt);
        int outputTokens = estimateTokens(text);
        log.info("[mock-llm] tier={} in={} out={}", tier, inputTokens, outputTokens);
        return new LlmResponse(text, inputTokens, outputTokens);
    }

    /** 提取提示词里用户问题部分（前缀之后） */
    private String extractUserBody(String prompt) {
        int idx = prompt.lastIndexOf("\n用户：");
        if (idx >= 0) {
            return prompt.substring(idx + 4);
        }
        return prompt.length() > 200 ? prompt.substring(0, 200) : prompt;
    }

    /** 按字节估算 token（1 汉字 ≈ 3 字节 ≈ 1.5 token，简化 1.3） */
    private int estimateTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int chars = s.length();
        int cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0x2E7F) {
                cjk++;
            }
        }
        return (int) Math.ceil((cjk * 1.3) + ((chars - cjk) * 0.3));
    }
}
