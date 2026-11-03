package com.goshare.assistant.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 行程/天气意图解析（纯本地逻辑）+ 行程无班次诚实兜底（真实数据来源，不编造）
 */
class TripWeatherRuleProviderTest {

    private static final Map<String, String> USER = Map.of("role", "user", "content", "");

    @Test
    void weatherCityFallsBackToHistory() {
        WeatherRuleProvider p = new WeatherRuleProvider(mock(RestTemplate.class), new ObjectMapper());
        WeatherRuleProvider.WeatherQuery q = p.resolve("11.4号的天气",
                List.of(Map.of("role", "user", "content", "邯郸今天天气怎么样")));
        assertNotNull(q);
        assertEquals("邯郸", q.city);
        assertEquals(LocalDate.now().getMonthValue(), q.date.getMonthValue());
        assertEquals(4, q.date.getDayOfMonth());
    }

    @Test
    void weatherCityNotFoundReturnsNullCity() {
        WeatherRuleProvider p = new WeatherRuleProvider(mock(RestTemplate.class), new ObjectMapper());
        WeatherRuleProvider.WeatherQuery q = p.resolve("明天天气怎么样", List.of());
        assertNotNull(q);
        assertNull(q.city);
    }

    @Test
    void weatherDateParsesTomorrow() {
        WeatherRuleProvider p = new WeatherRuleProvider(mock(RestTemplate.class), new ObjectMapper());
        WeatherRuleProvider.WeatherQuery q = p.resolve("明天冷不冷", List.of());
        assertNotNull(q);
        assertEquals(LocalDate.now().plusDays(1), q.date);
    }

    @Test
    void tripResolveFromHistoryMergesCities() {
        TripRuleProvider p = new TripRuleProvider(mock(RestTemplate.class), new ObjectMapper());
        // 「我想去邯郸」→ 缺出发地 → 诚实反问
        TripRuleProvider.TripQuery q1 = p.resolve("我想去邯郸", List.of());
        assertEquals("clarify", q1.mode);
        assertEquals("邯郸", q1.to);
        // 补发「北京」→ 历史终点邯郸 + 单城市北京 → from=北京, to=邯郸
        TripRuleProvider.TripQuery q2 = p.resolve("北京",
                List.of(Map.of("role", "user", "content", "我想去邯郸")));
        assertEquals("fetch", q2.mode);
        assertEquals("北京", q2.from);
        assertEquals("邯郸", q2.to);
    }

    @Test
    void tripDateOnlyFollowUpAsksClarify() {
        TripRuleProvider p = new TripRuleProvider(mock(RestTemplate.class), new ObjectMapper());
        TripRuleProvider.TripQuery q = p.resolve("11.4号的呢", List.of());
        assertEquals("ask", q.mode);
        assertTrue(q.hint.contains("天气"));
        assertTrue(q.hint.contains("班次"));
    }

    @Test
    void tripFetchEmptyDataSaysNoTrips() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.getForObject(anyString(), eq(String.class), anyMap()))
                .thenReturn("{\"code\":200,\"data\":[]}");
        TripRuleProvider p = new TripRuleProvider(rt, new ObjectMapper());
        TripRuleProvider.TripQuery q = p.resolve("北京到邯郸有车吗", List.of());
        assertEquals("fetch", q.mode);
        String answer = p.fetch(q);
        assertTrue(answer.contains("暂无班次"));
        assertTrue(answer.contains("北京"));
        assertTrue(answer.contains("邯郸"));
    }
}
