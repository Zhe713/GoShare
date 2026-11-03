package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.llm.TimeRuleProvider;
import com.goshare.assistant.llm.TripRuleProvider;
import com.goshare.assistant.llm.WeatherRuleProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 各工具执行分支：正常取数 / 空参数反问 / 参数解析失败兜底 / 服务不可用如实告知 / 时间实时返回
 */
class AssistantToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void weatherToolExecutesWithCityAndDate() {
        WeatherRuleProvider weather = mock(WeatherRuleProvider.class);
        when(weather.fetch(any())).thenReturn("【邯郸11月4日天气】小雨，10.4~16.3℃。");
        WeatherTool tool = new WeatherTool(weather, objectMapper);
        assertEquals("【邯郸11月4日天气】小雨，10.4~16.3℃。",
                tool.execute("{\"city\":\"邯郸\",\"date\":\"2026-11-04\"}"));
    }

    @Test
    void weatherToolBlankCityAsksForCity() {
        WeatherTool tool = new WeatherTool(mock(WeatherRuleProvider.class), objectMapper);
        assertTrue(tool.execute("{\"city\":\"\"}").contains("城市不能为空"));
    }

    @Test
    void weatherToolMalformedArgsReturnsFailure() {
        WeatherTool tool = new WeatherTool(mock(WeatherRuleProvider.class), objectMapper);
        assertTrue(tool.execute("not-json").startsWith("天气查询参数解析失败"));
    }

    @Test
    void weatherToolServiceDownReturnsHonestMessage() {
        WeatherRuleProvider weather = mock(WeatherRuleProvider.class);
        when(weather.fetch(any())).thenReturn(null);
        WeatherTool tool = new WeatherTool(weather, objectMapper);
        assertTrue(tool.execute("{\"city\":\"邯郸\"}").contains("天气服务暂时不可用"));
    }

    @Test
    void tripToolExecutesWithFromTo() {
        TripRuleProvider trip = mock(TripRuleProvider.class);
        when(trip.fetch(any())).thenReturn("【北京→邯郸 班次】明天 18:00 出发 · 45元 · 余2座。");
        TripSearchTool tool = new TripSearchTool(trip, objectMapper);
        assertEquals("【北京→邯郸 班次】明天 18:00 出发 · 45元 · 余2座。",
                tool.execute("{\"fromCity\":\"北京\",\"toCity\":\"邯郸\"}"));
    }

    @Test
    void tripToolBlankCityAsksForCity() {
        TripSearchTool tool = new TripSearchTool(mock(TripRuleProvider.class), objectMapper);
        assertTrue(tool.execute("{\"fromCity\":\"\",\"toCity\":\"\"}").contains("出发地和目的地不能为空"));
    }

    @Test
    void tripToolMalformedArgsReturnsFailure() {
        TripSearchTool tool = new TripSearchTool(mock(TripRuleProvider.class), objectMapper);
        assertTrue(tool.execute("not-json").startsWith("行程查询参数解析失败"));
    }

    @Test
    void currentTimeToolReturnsRealTime() {
        CurrentTimeTool tool = new CurrentTimeTool(new TimeRuleProvider());
        String r = tool.execute("{}");
        assertTrue(r.matches("现在是\\d{4}年\\d{1,2}月\\d{1,2}日 \\d{2}:\\d{2}。"),
                "实时时间格式不符: " + r);
    }
}
