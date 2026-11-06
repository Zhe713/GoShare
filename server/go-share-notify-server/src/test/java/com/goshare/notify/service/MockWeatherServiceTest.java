package com.goshare.notify.service;

import com.goshare.notify.dto.WeatherVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
/**
 * Mock 天气数据源（默认模式）：内置城市表 + 未收录城市的温和兜底
 */
class MockWeatherServiceTest {

    private final MockWeatherService service = new MockWeatherService();

    @Test
    void knownCityReturnsBuiltInWeather() {
        WeatherVO vo = service.query("北京");

        assertEquals("北京", vo.getCity());
        assertEquals("晴", vo.getWeather());
        assertEquals("20℃", vo.getTemperature());
        assertTrue(vo.getAdvice().contains("适合出行"), vo.getAdvice());
    }

    @Test
    void rainyCityAdviceRemindsUmbrella() {
        WeatherVO vo = service.query("邯郸");

        assertEquals("小雨", vo.getWeather());
        assertTrue(vo.getAdvice().contains("雨具"), vo.getAdvice());
    }

    @Test
    void unknownCityFallsBackWithoutThrowing() {
        WeatherVO vo = service.query("克拉玛依");

        assertEquals("克拉玛依", vo.getCity());
        assertEquals("晴", vo.getWeather());
        assertEquals("20℃", vo.getTemperature());
        assertTrue(vo.getAdvice().contains("暂未获取"), vo.getAdvice());
    }

    @Test
    void dateOverloadKeepsLegacyBehaviour() {
        // Mock 源没有分日数据：带日期的查询与不带日期等价（接口默认实现）
        WeatherVO withDate = service.query("石家庄", LocalDate.of(2026, 12, 1));

        assertEquals(service.query("石家庄").getWeather(), withDate.getWeather());
        assertEquals(service.query("石家庄").getTemperature(), withDate.getTemperature());
    }
}
