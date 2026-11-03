package com.goshare.notify.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.notify.dto.WeatherVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 天气数据源（真实数据·免费无 Key：Open-Meteo 全球天气）
 * 流程：城市名 → 地理编码（geocoding）→ 实时 forecast（current + daily 高低温）
 * WMO 天气码映射为中文，出行建议按天气码/温度规则生成（非编造数据）。
 * 仅当 goshare.weather.mode=http 时启用；接口异常时返回「暂不可用」兜底，绝不返回模拟天气。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "goshare.weather.mode", havingValue = "http")
public class HttpWeatherService implements WeatherService {

    /** Open-Meteo 地理编码：城市名 → 经纬度（免费无 Key） */
    private static final String GEOCODE_URL =
            "https://geocoding-api.open-meteo.com/v1/search?name={city}&count=1&language=zh&format=json";

    /** Open-Meteo 实时天气（current 实况 + daily 未来 7 天高低温；timezone=auto 按坐标自动判时区） */
    private static final String FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}"
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                    + "&timezone=auto&forecast_days=7";

    /** WMO 天气码 → 中文天气现象（0-99 官方码） */
    private static final Map<Integer, String> WMO = new HashMap<>();

    static {
        WMO.put(0, "晴");
        WMO.put(1, "晴间多云");
        WMO.put(2, "多云");
        WMO.put(3, "阴");
        WMO.put(45, "雾");
        WMO.put(48, "雾凇");
        WMO.put(51, "毛毛雨");
        WMO.put(53, "小雨");
        WMO.put(55, "中雨");
        WMO.put(56, "冻毛毛雨");
        WMO.put(57, "冻雨");
        WMO.put(61, "小雨");
        WMO.put(63, "中雨");
        WMO.put(65, "大雨");
        WMO.put(66, "冻雨");
        WMO.put(67, "冻雨");
        WMO.put(71, "小雪");
        WMO.put(73, "中雪");
        WMO.put(75, "大雪");
        WMO.put(77, "雪粒");
        WMO.put(80, "阵雨");
        WMO.put(81, "强阵雨");
        WMO.put(82, "暴雨");
        WMO.put(85, "阵雪");
        WMO.put(86, "强阵雪");
        WMO.put(95, "雷阵雨");
        WMO.put(96, "雷阵雨伴冰雹");
        WMO.put(99, "雷暴伴冰雹");
    }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpWeatherService(@Qualifier("plainRestTemplate") RestTemplate restTemplate,
                              ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public WeatherVO query(String city) {
        return query(city, null);
    }

    @Override
    public WeatherVO query(String city, LocalDate date) {
        try {
            // 1. 地理编码：城市名 → 经纬度
            String geoBody = restTemplate.getForObject(GEOCODE_URL, String.class, city);
            JsonNode geo = objectMapper.readTree(geoBody);
            JsonNode first = geo.path("results").path(0);
            double lat = first.path("latitude").asDouble(Double.NaN);
            double lon = first.path("longitude").asDouble(Double.NaN);
            String resolvedName = first.path("name").asText(city);
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                log.warn("[weather-http] 未找到城市坐标 city={}", city);
                return unavailable(city);
            }
            // 2. 未来 7 天 forecast（daily 数组按当地时间从今天起排）
            String fcBody = restTemplate.getForObject(FORECAST_URL, String.class, lat, lon);
            JsonNode fc = objectMapper.readTree(fcBody);
            JsonNode daily = fc.path("daily");
            // 目标日在 daily.time 中的下标：date 为空取今天(0)，否则按时间数组精确匹配
            int idx = 0;
            if (date != null) {
                String target = date.toString();
                JsonNode times = daily.path("time");
                for (int i = 0; i < times.size(); i++) {
                    if (target.equals(times.path(i).asText())) {
                        idx = i;
                        break;
                    }
                }
            }
            int code = daily.path("weather_code").path(idx).asInt(3);
            String weather = WMO.getOrDefault(code, "未知");
            String tempLow = daily.path("temperature_2m_min").path(idx).asText("");
            String tempHigh = daily.path("temperature_2m_max").path(idx).asText("");
            String temp;
            double tempC;
            if (idx == 0) {
                // 今天：实时气温 + 今日高低温
                JsonNode current = fc.path("current");
                code = current.path("weather_code").asInt(code);
                weather = WMO.getOrDefault(code, weather);
                temp = current.path("temperature_2m").asText("--") + "℃";
                tempC = current.path("temperature_2m").asDouble(Double.NaN);
                if (!tempHigh.isBlank() && !tempLow.isBlank()) {
                    temp = temp + "（今日 " + tempLow + "~" + tempHigh + "℃）";
                }
            } else {
                // 未来日期：当天高低温区间（无实时气温）
                temp = tempLow + "~" + tempHigh + "℃";
                tempC = (parseTemp(tempLow) + parseTemp(tempHigh)) / 2.0;
            }
            WeatherVO vo = new WeatherVO();
            vo.setCity(resolvedName);
            vo.setWeather(weather);
            vo.setTemperature(temp);
            vo.setAdvice(advice(code, tempC));
            return vo;
        } catch (Exception e) {
            log.warn("[weather-http] 天气接口调用失败 city={} date={} err={}", city, date, e.getMessage());
            return unavailable(city);
        }
    }

    private double parseTemp(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /** 接口异常/城市无坐标时的诚实兜底（不返回模拟天气数据） */
    private WeatherVO unavailable(String city) {
        WeatherVO vo = new WeatherVO();
        vo.setCity(city);
        vo.setWeather("暂不可用");
        vo.setTemperature("--");
        vo.setAdvice("实时天气数据获取失败，出行前请留意当地天气。");
        return vo;
    }

    /** 基于 WMO 天气码与当前温度生成出行建议（规则化，不编造数值） */
    private String advice(int code, double tempC) {
        boolean rain = code >= 51 && code <= 67 || code >= 80 && code <= 82;
        boolean snow = code >= 71 && code <= 77 || code >= 85 && code <= 86;
        boolean storm = code >= 95;
        StringBuilder sb = new StringBuilder();
        if (storm) {
            sb.append("有雷暴，尽量避开户外行程，注意防雷。");
        } else if (rain) {
            sb.append("有降水，路面湿滑，请备好雨具并提前出门。");
        } else if (snow) {
            sb.append("有降雪，路面易结冰，请减速慢行、注意保暖。");
        } else if (code == 45 || code == 48) {
            sb.append("有雾，能见度较低，驾车请减速并开启雾灯。");
        } else if (code <= 3) {
            sb.append("天气较好，适合出行。");
        }
        if (!Double.isNaN(tempC)) {
            if (tempC >= 33) {
                sb.append("气温较高，注意防暑补水。");
            } else if (tempC <= 5) {
                sb.append("气温偏低，请注意保暖。");
            }
        }
        return sb.length() == 0 ? "出行请留意实时天气变化。" : sb.toString();
    }
}
