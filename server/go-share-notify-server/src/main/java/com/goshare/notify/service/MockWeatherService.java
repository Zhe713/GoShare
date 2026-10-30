package com.goshare.notify.service;

import com.goshare.notify.config.WeatherProperties;
import com.goshare.notify.dto.WeatherVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mock 天气数据源（默认，零外部依赖可演示）
 * 可插拔设计：goshare.weather.mode=http 时由 HttpWeatherService 接管，此处不生效
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "goshare.weather.mode", havingValue = "mock", matchIfMissing = true)
public class MockWeatherService implements WeatherService {

    /** 内置演示天气表（城市 → 天气/温度/建议） */
    private static final Map<String, WeatherVO> MOCK = new LinkedHashMap<>();

    static {
        put("石家庄", "多云", "18℃", "体感舒适，建议穿外套出行，早晚偏凉。");
        put("北京", "晴", "20℃", "天气晴好，适合出行；城区早晚有轻微拥堵，建议提前出发。");
        put("邯郸", "小雨", "16℃", "有小雨，路面湿滑，请提前出门并备好雨具。");
        put("天津", "晴", "21℃", "晴好天气，注意午后紫外线，出行记得补水。");
        put("保定", "阴", "17℃", "阴天，风力较小，适合出行，记得带件外套。");
    }

    private static void put(String city, String weather, String temp, String advice) {
        WeatherVO vo = new WeatherVO();
        vo.setCity(city);
        vo.setWeather(weather);
        vo.setTemperature(temp);
        vo.setAdvice(advice);
        MOCK.put(city, vo);
    }

    @Override
    public WeatherVO query(String city) {
        WeatherVO vo = MOCK.get(city);
        if (vo != null) {
            return vo;
        }
        WeatherVO fallback = new WeatherVO();
        fallback.setCity(city);
        fallback.setWeather("晴");
        fallback.setTemperature("20℃");
        fallback.setAdvice("暂未获取该城市实时天气，按晴天出行准备即可。");
        return fallback;
    }
}
