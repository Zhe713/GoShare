package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.llm.LunarService;
import com.goshare.assistant.llm.TimeRuleProvider;
import com.goshare.assistant.llm.TripRuleProvider;
import com.goshare.assistant.llm.WeatherRuleProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具注册表：工具描述生成 / 按名派发执行 / 未注册与异常兜底
 */
class ToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ToolRegistry registry(TripRuleProvider trip, WeatherRuleProvider weather) {
        return new ToolRegistry(List.of(
                new TripSearchTool(trip, objectMapper),
                new WeatherTool(weather, objectMapper),
                new LunarTool(new LunarService(), objectMapper),
                new CurrentTimeTool(new TimeRuleProvider())
        ));
    }

    @Test
    void toOpenAiToolsContainsAllFourTools() {
        ToolRegistry registry = registry(mock(TripRuleProvider.class), mock(WeatherRuleProvider.class));
        List<Map<String, Object>> tools = registry.toOpenAiTools();
        assertEquals(4, tools.size());
        Map<String, Object> first = tools.get(0);
        assertEquals("function", first.get("type"));
        assertEquals("search_trips", ((Map<?, ?>) first.get("function")).get("name"));
    }

    @Test
    void executesLunarToolWithRealCalendar() {
        ToolRegistry registry = registry(mock(TripRuleProvider.class), mock(WeatherRuleProvider.class));
        String result = registry.execute("get_lunar_date", "{\"date\":\"2026-11-03\"}");
        assertEquals("农历丙午（马）年九月廿五", result);
    }

    @Test
    void executesTripToolThroughTripProvider() {
        TripRuleProvider trip = mock(TripRuleProvider.class);
        when(trip.fetch(any())).thenReturn("【北京→邯郸 班次】明天 18:00 出发 · 45元 · 余2座。");
        ToolRegistry registry = registry(trip, mock(WeatherRuleProvider.class));
        String result = registry.execute("search_trips", "{\"fromCity\":\"北京\",\"toCity\":\"邯郸\"}");
        assertEquals("【北京→邯郸 班次】明天 18:00 出发 · 45元 · 余2座。", result);
    }

    @Test
    void unknownToolReturnsHonestMessage() {
        ToolRegistry registry = registry(mock(TripRuleProvider.class), mock(WeatherRuleProvider.class));
        String result = registry.execute("no_such_tool", "{}");
        assertEquals("没有找到可用的工具来回答这个问题。", result);
    }

    @Test
    void malformedArgumentsReturnFailureNotCrash() {
        ToolRegistry registry = registry(mock(TripRuleProvider.class), mock(WeatherRuleProvider.class));
        String result = registry.execute("get_lunar_date", "not-json");
        assertTrue(result.startsWith("农历查询参数解析失败"));
    }

    @Test
    void toolExecutionExceptionIsCaughtByRegistry() {
        AssistantTool boom = mock(AssistantTool.class);
        when(boom.name()).thenReturn("boom_tool");
        when(boom.execute(anyString())).thenThrow(new RuntimeException("boom"));
        ToolRegistry registry = new ToolRegistry(List.of(boom));

        String result = registry.execute("boom_tool", "{}");

        assertTrue(result.startsWith("查询失败"));
        assertTrue(result.contains("boom"));
    }
}
