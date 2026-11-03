package com.goshare.assistant.llm;

import com.goshare.assistant.config.LlmProperties;
import com.goshare.assistant.llm.tool.ToolRegistry;
import com.goshare.assistant.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Function Calling 工具通道：
 * 模型发起工具调用 → 网关执行工具 → 回填 tool 消息后二次调用 → 输出最终文本；
 * 模型不调工具直接结束；工具循环受轮数上限保护（防死循环）。
 */
class LlmGatewayToolCallTest {

    private LlmClient llmClient;
    private ToolRegistry toolRegistry;
    private LlmGateway gateway;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        LlmProperties properties = new LlmProperties();
        properties.setDataVersion(2);
        toolRegistry = mock(ToolRegistry.class);
        gateway = new LlmGateway(llmClient, redisTemplate, mock(LlmCallLogMapper.class), properties,
                mock(WeatherRuleProvider.class), mock(TripRuleProvider.class), mock(TimeRuleProvider.class),
                toolRegistry, mock(LunarRuleProvider.class));
    }

    @Test
    void executesToolCallThenReturnsFinalText() {
        when(toolRegistry.toOpenAiTools()).thenReturn(List.of());
        when(llmClient.chat(anyList(), anyList(), any())).thenAnswer(inv -> {
            List<LlmClient.ToolMessage> msgs = inv.getArgument(0);
            boolean hasToolResult = msgs.stream().anyMatch(m -> "tool".equals(m.role()));
            if (!hasToolResult) {
                return new LlmClient.ToolChatResponse(null,
                        List.of(new LlmClient.ToolCall("call_1", "get_lunar_date", "{\"date\":\"2026-11-03\"}")),
                        10, 5);
            }
            return new LlmClient.ToolChatResponse("农历丙午（马）年九月廿五", 20, 10);
        });
        when(toolRegistry.execute("get_lunar_date", "{\"date\":\"2026-11-03\"}"))
                .thenReturn("农历丙午（马）年九月廿五");

        LlmResult result = gateway.ask("FAQ天气", "今天是农历几号", LlmClient.Tier.SMALL);

        assertEquals("农历丙午（马）年九月廿五", result.getText());
        assertEquals(20, result.getInputTokens());
        assertEquals(10, result.getOutputTokens());
        verify(toolRegistry).execute("get_lunar_date", "{\"date\":\"2026-11-03\"}");
        verify(llmClient, times(2)).chat(anyList(), anyList(), any());
    }

    @Test
    void directAnswerWithoutToolCallFinishesInOneRound() {
        when(toolRegistry.toOpenAiTools()).thenReturn(List.of());
        when(llmClient.chat(anyList(), anyList(), any()))
                .thenReturn(new LlmClient.ToolChatResponse("你好，有什么可以帮你？", 8, 4));

        LlmResult result = gateway.ask("FAQ天气", "你好", LlmClient.Tier.SMALL);

        assertEquals("你好，有什么可以帮你？", result.getText());
        verify(llmClient, times(1)).chat(anyList(), anyList(), any());
        verify(toolRegistry, never()).execute(anyString(), anyString());
    }

    @Test
    void toolLoopIsBoundedByMaxRounds() {
        when(toolRegistry.toOpenAiTools()).thenReturn(List.of());
        // 模型每次都坚持调工具（异常行为），网关必须在 MAX_TOOL_ROUNDS 轮后终止并降级
        when(llmClient.chat(anyList(), anyList(), any())).thenAnswer(inv ->
                new LlmClient.ToolChatResponse(null,
                        List.of(new LlmClient.ToolCall("call_x", "get_current_time", "{}")), 10, 5));
        when(toolRegistry.execute("get_current_time", "{}")).thenReturn("现在是2026年11月3日 15:00。");

        LlmResult result = gateway.ask("FAQ天气", "现在几点了", LlmClient.Tier.SMALL);

        assertFalse(result.getText().isBlank());
        assertFalse(result.getText().contains("null"));
        verify(llmClient, times(3)).chat(anyList(), anyList(), any());
        verify(toolRegistry, times(3)).execute("get_current_time", "{}");
    }

    @Test
    void toolFailureIsFedBackAsToolResultNotCrash() {
        when(toolRegistry.toOpenAiTools()).thenReturn(List.of());
        when(llmClient.chat(anyList(), anyList(), any())).thenAnswer(inv -> {
            List<LlmClient.ToolMessage> msgs = inv.getArgument(0);
            boolean hasToolResult = msgs.stream().anyMatch(m -> "tool".equals(m.role()));
            if (!hasToolResult) {
                return new LlmClient.ToolChatResponse(null,
                        List.of(new LlmClient.ToolCall("call_2", "get_weather", "{\"city\":\"邯郸\"}")), 9, 4);
            }
            return new LlmClient.ToolChatResponse("天气服务暂时不可用，请稍后再试。", 14, 7);
        });
        when(toolRegistry.execute("get_weather", "{\"city\":\"邯郸\"}"))
                .thenReturn("天气服务暂时不可用，请稍后再试。");

        LlmResult result = gateway.ask("FAQ天气", "邯郸今天天气", LlmClient.Tier.SMALL);

        assertEquals("天气服务暂时不可用，请稍后再试。", result.getText());
        verify(toolRegistry).execute("get_weather", "{\"city\":\"邯郸\"}");
    }
}
