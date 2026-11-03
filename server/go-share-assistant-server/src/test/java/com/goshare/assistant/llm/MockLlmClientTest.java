package com.goshare.assistant.llm;

import com.goshare.assistant.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mock LLM 客户端：模板话术组装 / 用户问题提取 / token 估算（零成本可演示）
 */
class MockLlmClientTest {

    private MockLlmClient client;

    @BeforeEach
    void setUp() {
        client = new MockLlmClient(new LlmProperties());
    }

    @Test
    void smallTierReturnsRuleAnswerTemplate() {
        LlmClient.LlmResponse resp = client.call("【系统】\n用户：取消订单会扣钱吗", LlmClient.Tier.SMALL);
        assertTrue(resp.text().contains("【小模型·规则答案】"));
        assertTrue(resp.text().contains("取消订单会扣钱吗"));
        assertTrue(resp.inputTokens() > 0);
        assertTrue(resp.outputTokens() > 0);
    }

    @Test
    void largeTierReturnsMockOutputTemplate() {
        LlmClient.LlmResponse resp = client.call("【系统】\n用户：帮我总结工单", LlmClient.Tier.LARGE);
        assertTrue(resp.text().contains("【大模型·mock 输出】"));
        assertTrue(resp.text().contains("帮我总结工单"));
    }

    @Test
    void extractsUserBodyAfterPrefix() {
        LlmClient.LlmResponse resp = client.call("固定前缀第一行\n用户：我要退款", LlmClient.Tier.SMALL);
        assertTrue(resp.text().contains("我要退款"));
        assertFalse(resp.text().contains("固定前缀第一行"));
    }

    @Test
    void longPromptWithoutPrefixTruncated() {
        String longText = "字".repeat(300);
        LlmClient.LlmResponse resp = client.call(longText, LlmClient.Tier.SMALL);
        // 无"用户："前缀时截断前 200 字符：正文部分不应超过 200 个"字"
        String body = resp.text().replace("【小模型·规则答案】", "");
        assertEquals(200, body.length());
        assertTrue(resp.inputTokens() > 0);
    }

    @Test
    void emptyPromptZeroTokens() {
        LlmClient.LlmResponse resp = client.call("", LlmClient.Tier.SMALL);
        assertEquals(0, resp.inputTokens());
    }

    @Test
    void chatReturnsMockTextWithoutToolCalls() {
        List<LlmClient.ToolMessage> msgs = List.of(
                new LlmClient.ToolMessage("system", "今天是2026年11月3日，周二。"),
                new LlmClient.ToolMessage("user", "农历"));
        LlmClient.ToolChatResponse resp = client.chat(msgs, List.of(), LlmClient.Tier.SMALL);
        assertTrue(resp.text().contains("农历"));
        assertTrue(resp.toolCalls().isEmpty());
        assertTrue(resp.inputTokens() > 0);
        assertTrue(resp.outputTokens() > 0);
    }

    @Test
    void chatLargeTierReturnsMockOutputTemplate() {
        LlmClient.ToolChatResponse resp = client.chat(
                List.of(new LlmClient.ToolMessage("user", "写个文案")), List.of(), LlmClient.Tier.LARGE);
        assertTrue(resp.text().contains("【大模型·mock 输出】"));
    }

    @Test
    void chatWithoutUserMessageFallsBackToPlaceholder() {
        LlmClient.ToolChatResponse resp = client.chat(
                List.of(new LlmClient.ToolMessage("system", "s")), List.of(), LlmClient.Tier.SMALL);
        assertTrue(resp.text().contains("（无用户消息）"));
    }
}
