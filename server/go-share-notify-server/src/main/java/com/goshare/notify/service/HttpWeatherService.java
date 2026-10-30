package com.goshare.notify.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.notify.config.WeatherProperties;
import com.goshare.notify.dto.WeatherVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP 天气数据源（可插拔真实 API：高德天气 v3 等）
 * 仅当 goshare.weather.mode=http 且配置有效 key 时启用；失败自动降级 mock 风格兜底文案
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "goshare.weather.mode", havingValue = "http")
public class HttpWeatherService implements WeatherService {

    private final WeatherProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public WeatherVO query(String city) {
        try {
            String url = properties.getHttpUrl() + "?key=" + properties.getApiKey()
                    + "&city=" + city + "&extensions=base";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String resp = restTemplate.postForObject(url,
                    new HttpEntity<>(Map.of(), headers), String.class);
            JsonNode node = objectMapper.readTree(resp);
            JsonNode live = node.path("lives").path(0);
            WeatherVO vo = new WeatherVO();
            vo.setCity(live.path("city").asText(city));
            vo.setWeather(live.path("weather").asText("未知"));
            vo.setTemperature(live.path("temperature").asText("--") + "℃");
            vo.setAdvice("出行请留意实时天气变化，注意安全。");
            return vo;
        } catch (Exception e) {
            log.warn("[weather-http] 天气接口调用失败，返回兜底: {}", e.getMessage());
            WeatherVO fallback = new WeatherVO();
            fallback.setCity(city);
            fallback.setWeather("未知");
            fallback.setTemperature("--");
            fallback.setAdvice("天气数据暂不可用，出行请留意实时天气。");
            return fallback;
        }
    }
}
