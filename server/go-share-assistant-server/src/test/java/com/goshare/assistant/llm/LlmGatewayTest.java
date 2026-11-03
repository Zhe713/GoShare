package com.goshare.assistant.llm;

import com.goshare.assistant.config.LlmProperties;
import com.goshare.assistant.entity.LlmCallLog;
import com.goshare.assistant.llm.tool.ToolRegistry;
import com.goshare.assistant.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LLM 网关四关卡 + 工具通道：答案缓存命中 / 规则与农历小模型先答 / Function Calling 兜底 / 缓存键版本化
 */
class LlmGatewayTest {

    private LlmClient llmClient;
    private StringRedisTemplate redisTemplate;
    private LlmCallLogMapper llmCallLogMapper;
    private LlmProperties properties;
    private ToolRegistry toolRegistry;
    private LunarRuleProvider lunarRuleProvider;
    private LlmGateway gateway;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        llmClient = mock(LlmClient.class);
        redisTemplate = mock(StringRedisTemplate.class);
        llmCallLogMapper = mock(LlmCallLogMapper.class);
        properties = new LlmProperties();
        properties.setDataVersion(2);
        toolRegistry = mock(ToolRegistry.class);
        lunarRuleProvider = mock(LunarRuleProvider.class);
        gateway = new LlmGateway(llmClient, redisTemplate, llmCallLogMapper, properties,
                mock(WeatherRuleProvider.class), mock(TripRuleProvider.class), mock(TimeRuleProvider.class),
                toolRegistry, lunarRuleProvider);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void cacheHitReturnsCachedWithoutCallingModel() {
        String question = "取消订单会扣钱吗";
        when(redisTemplate.opsForValue().get(anyString())).thenReturn("缓存的规则答案");

        LlmResult result = gateway.ask("FAQ天气", question, LlmClient.Tier.SMALL);

        assertTrue(result.isFromCache());
        assertEquals("cache", result.getModelName());
        assertFalse(result.isGateSkipped());
        verify(llmClient, never()).call(anyString(), any());
        verify(llmClient, never()).chat(anyList(), anyList(), any());
        // 记账：缓存命中标记
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(llmCallLogMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getCacheHit());
    }

    @Test
    void ruleAnswerHitSkipsModelAndWritesCache() {
        String question = "取消订单会扣钱吗"; // 规则模板命中
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);

        LlmResult result = gateway.ask("FAQ天气", question, LlmClient.Tier.SMALL);

        assertFalse(result.isFromCache());
        assertTrue(result.isGateSkipped());
        assertEquals("小模型(规则)", result.getModelName());
        verify(llmClient, never()).call(anyString(), any());
        // 规则答案回写缓存
        verify(redisTemplate.opsForValue()).set(anyString(), anyString(), eq(Duration.ofHours(12)));
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(llmCallLogMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getGateSkipped());
    }

    @Test
    void lunarIntentHitSkipsModelWithRealLunar() {
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(lunarRuleProvider.maybeAnswer("农历")).thenReturn("今天是农历丙午（马）年九月廿五。");

        LlmResult result = gateway.ask("FAQ天气", "农历", LlmClient.Tier.SMALL);

        assertFalse(result.isFromCache());
        assertTrue(result.isGateSkipped());
        assertEquals("小模型(农历)", result.getModelName());
        assertEquals("今天是农历丙午（马）年九月廿五。", result.getText());
        verify(llmClient, never()).chat(anyList(), anyList(), any());
    }

    @Test
    void nonRuleQuestionGoesThroughToolChannelAndWritesCache() {
        String question = "帮我写一首关于秋风的藏头诗"; // 不在规则模板内，兜底走工具通道
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(toolRegistry.toOpenAiTools()).thenReturn(List.of());
        when(llmClient.chat(anyList(), anyList(), eq(LlmClient.Tier.SMALL)))
                .thenReturn(new LlmClient.ToolChatResponse("秋日私语…", 120, 60));

        LlmResult result = gateway.ask("B1工单摘要", question, LlmClient.Tier.SMALL);

        assertFalse(result.isFromCache());
        assertFalse(result.isGateSkipped());
        assertEquals("小模型", result.getModelName());
        assertEquals(120, result.getInputTokens());
        assertEquals(60, result.getOutputTokens());
        verify(llmClient).chat(anyList(), anyList(), eq(LlmClient.Tier.SMALL));
        verify(llmClient, never()).call(anyString(), any());
        verify(redisTemplate.opsForValue()).set(anyString(), eq("秋日私语…"), eq(Duration.ofHours(12)));
    }

    @Test
    void toolChannelMessagesCarrySystemPrefixAndBindingRule() {
        // 关卡3：固定前缀 + 工具绑定约束强制拼入 system 消息
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(toolRegistry.toOpenAiTools()).thenReturn(List.of());
        when(llmClient.chat(anyList(), anyList(), any()))
                .thenReturn(new LlmClient.ToolChatResponse("x", 1, 1));

        gateway.ask("FAQ天气", "明天天气怎么样", LlmClient.Tier.LARGE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmClient.ToolMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).chat(captor.capture(), anyList(), eq(LlmClient.Tier.LARGE));
        List<LlmClient.ToolMessage> msgs = captor.getValue();
        assertTrue(msgs.get(0).content().contains(LlmGateway.SYSTEM_PREFIX));
        assertTrue(msgs.get(0).content().contains(LlmGateway.TOOL_BINDING_RULE));
        assertEquals("user", msgs.get(msgs.size() - 1).role());
        assertTrue(msgs.get(msgs.size() - 1).content().contains("明天天气怎么样"));
    }

    @Test
    void cacheKeyContainsDataVersion() {
        // 数据版本 +1 → 键变化 → 旧答案自动作废
        String hash = LlmGatewayTest.stableHash();
        String key = gateway.cacheKey(hash);
        assertEquals("ai:ans:v2:" + hash, key);
        properties.setDataVersion(3);
        assertEquals("ai:ans:v3:" + hash, gateway.cacheKey(hash));
    }

    @Test
    void prewarmRunsAllHotQuestionsWithoutError() {
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(toolRegistry.toOpenAiTools()).thenReturn(List.of());
        when(llmClient.chat(anyList(), anyList(), any()))
                .thenReturn(new LlmClient.ToolChatResponse("预生成答案", 10, 20));
        assertDoesNotThrow(() -> gateway.prewarmHotQuestions());
        assertEquals(LlmGateway.HOT_QUESTIONS.size(), 5);
    }

    /** 稳定哈希（避免依赖内部实现） */
    private static String stableHash() {
        return org.springframework.util.DigestUtils.md5DigestAsHex(
                "any-hash".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
