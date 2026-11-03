package com.goshare.assistant.llm;

import com.goshare.assistant.config.LlmProperties;
import com.goshare.assistant.entity.LlmCallLog;
import com.goshare.assistant.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LLM 网关四关卡：答案缓存命中 / 规则小模型先答 / 直调模型记账 / 缓存键版本化
 */
class LlmGatewayTest {

    private LlmClient llmClient;
    private StringRedisTemplate redisTemplate;
    private LlmCallLogMapper llmCallLogMapper;
    private LlmProperties properties;
    private LlmGateway gateway;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        llmClient = mock(LlmClient.class);
        redisTemplate = mock(StringRedisTemplate.class);
        llmCallLogMapper = mock(LlmCallLogMapper.class);
        properties = new LlmProperties();
        properties.setDataVersion(2);
        gateway = new LlmGateway(llmClient, redisTemplate, llmCallLogMapper, properties, mock(WeatherRuleProvider.class));
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
    void nonRuleQuestionCallsModelAndWritesCache() {
        String question = "帮我写一首关于秋风的藏头诗"; // 不在规则模板内，直调小模型
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(llmClient.call(anyString(), eq(LlmClient.Tier.SMALL)))
                .thenReturn(new LlmClient.LlmResponse("秋日私语…", 120, 60));

        LlmResult result = gateway.ask("B1工单摘要", question, LlmClient.Tier.SMALL);

        assertFalse(result.isFromCache());
        assertFalse(result.isGateSkipped());
        assertEquals("小模型", result.getModelName());
        assertEquals(120, result.getInputTokens());
        assertEquals(60, result.getOutputTokens());
        verify(llmClient).call(argThat(p -> p.startsWith(LlmGateway.SYSTEM_PREFIX)), any());
        verify(redisTemplate.opsForValue()).set(anyString(), eq("秋日私语…"), eq(Duration.ofHours(12)));
    }

    @Test
    void promptAlwaysPrefixedWithSystemPrefix() {
        // 关卡3：固定前缀强制拼接（厂商缓存复用 + 防注入）
        when(redisTemplate.opsForValue().get(anyString())).thenReturn(null);
        when(llmClient.call(anyString(), any())).thenReturn(new LlmClient.LlmResponse("x", 1, 1));
        gateway.ask("FAQ天气", "明天天气怎么样", LlmClient.Tier.LARGE);
        verify(llmClient).call(argThat(p -> p.contains("用户：明天天气怎么样")
                && p.startsWith(LlmGateway.SYSTEM_PREFIX)), eq(LlmClient.Tier.LARGE));
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
        when(llmClient.call(anyString(), any()))
                .thenReturn(new LlmClient.LlmResponse("预生成答案", 10, 20));
        assertDoesNotThrow(() -> gateway.prewarmHotQuestions());
        assertEquals(LlmGateway.HOT_QUESTIONS.size(), 5);
    }

    /** 稳定哈希（避免依赖内部实现） */
    private static String stableHash() {
        return org.springframework.util.DigestUtils.md5DigestAsHex(
                "any-hash".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
