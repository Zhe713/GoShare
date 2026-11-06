package com.goshare.notify.controller;

import com.goshare.common.result.Result;
import com.goshare.notify.dto.WeatherVO;
import com.goshare.notify.service.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 通知控制器：天气查询透传 + 发车提醒的订单/行程编排与三种失败兜底
 */
class NotifyControllerTest {

    private WeatherService weatherService;
    private RestTemplate restTemplate;
    private NotifyController controller;

    @BeforeEach
    void setUp() {
        weatherService = mock(WeatherService.class);
        restTemplate = mock(RestTemplate.class);
        controller = new NotifyController(weatherService, restTemplate);
        ReflectionTestUtils.setField(controller, "orderUrl", "http://order-server");
        ReflectionTestUtils.setField(controller, "tripUrl", "http://trip-server");
    }

    private WeatherVO weather(String city, String w, String t, String advice) {
        WeatherVO vo = new WeatherVO();
        vo.setCity(city);
        vo.setWeather(w);
        vo.setTemperature(t);
        vo.setAdvice(advice);
        return vo;
    }

    private ResponseEntity<Result<Map<String, Object>>> wrap(Map<String, Object> data) {
        return ResponseEntity.ok(Result.success(data));
    }

    @SuppressWarnings("unchecked")
    private void stubOrder(Map<String, Object> data) {
        when(restTemplate.exchange(eq("http://order-server/api/order/internal/O-1"), eq(HttpMethod.GET), isNull(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(wrap(data));
    }

    @SuppressWarnings("unchecked")
    private void stubTrip(Map<String, Object> data) {
        when(restTemplate.exchange(eq("http://trip-server/api/trip/35"), eq(HttpMethod.GET), isNull(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(wrap(data));
    }

    @Test
    void weatherDelegatesCityAndDate() {
        when(weatherService.query("北京", LocalDate.of(2026, 11, 6)))
                .thenReturn(weather("北京", "小雪", "-3~2℃", "有降雪。"));

        Result<WeatherVO> r = controller.weather("北京", LocalDate.of(2026, 11, 6));

        assertEquals(200, r.getCode());
        assertEquals("小雪", r.getData().getWeather());
        verify(weatherService).query("北京", LocalDate.of(2026, 11, 6));
    }

    @Test
    void weatherWithoutDatePassesNull() {
        when(weatherService.query(eq("北京"), isNull()))
                .thenReturn(weather("北京", "晴", "20℃", "适合出行。"));

        Result<WeatherVO> r = controller.weather("北京", null);

        assertEquals(200, r.getCode());
        assertEquals("晴", r.getData().getWeather());
    }

    @Test
    void tripReminderComposesHumanReadableText() {
        Map<String, Object> order = new HashMap<>();
        order.put("tripId", 35);
        stubOrder(order);

        Map<String, Object> trip = new HashMap<>();
        trip.put("fromCity", "北京");
        trip.put("toCity", "邯郸");
        trip.put("departureTime", "2026-11-06T18:00:00");
        stubTrip(trip);

        when(weatherService.query("北京")).thenReturn(weather("北京", "小雨", "16℃", "请备好雨具。"));

        Result<Map<String, Object>> r = controller.tripReminder("O-1");

        assertEquals(200, r.getCode());
        Map<String, Object> d = r.getData();
        assertEquals("O-1", d.get("orderNo"));
        assertEquals("北京", d.get("fromCity"));
        assertEquals("邯郸", d.get("toCity"));
        assertEquals("小雨 16℃", d.get("weather"));
        String content = String.valueOf(d.get("content"));
        assertTrue(content.contains("【发车提醒】"), content);
        assertTrue(content.contains("北京→邯郸"), content);
        assertTrue(content.contains("2026-11-06 18:00"), content);
        assertTrue(content.contains("请备好雨具"), content);
    }

    @Test
    void tripReminderReturns404WhenOrderHasNoTripId() {
        stubOrder(new HashMap<>());

        Result<Map<String, Object>> r = controller.tripReminder("O-1");

        assertEquals(404, r.getCode());
        assertEquals("订单不存在", r.getMessage());
        // 订单缺失时不应再调行程服务
        verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.GET), isNull(),
                any(ParameterizedTypeReference.class));
    }

    @Test
    void tripReminderReturns404WhenOrderBodyIsNull() {
        // 订单服务返回空 body（异常降级场景）也不能抛 NPE
        when(restTemplate.exchange(eq("http://order-server/api/order/internal/O-1"), eq(HttpMethod.GET), isNull(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok(null));

        Result<Map<String, Object>> r = controller.tripReminder("O-1");

        assertEquals(404, r.getCode());
    }

    @Test
    void tripReminderReturns500WhenTripServiceEmpty() {
        Map<String, Object> order = new HashMap<>();
        order.put("tripId", 35);
        stubOrder(order);
        stubTrip(null);

        Result<Map<String, Object>> r = controller.tripReminder("O-1");

        assertEquals(500, r.getCode());
        assertEquals("行程服务暂不可用", r.getMessage());
    }

    @Test
    void tripReminderSwallowsExceptions() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), isNull(),
                any(ParameterizedTypeReference.class))).thenThrow(new RuntimeException("order down"));

        Result<Map<String, Object>> r = controller.tripReminder("O-1");

        assertEquals(500, r.getCode());
        assertEquals("发车提醒暂不可用", r.getMessage());
    }
}
