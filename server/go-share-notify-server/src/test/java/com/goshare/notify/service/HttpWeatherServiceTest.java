package com.goshare.notify.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.notify.dto.WeatherVO;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实天气数据源：地理编码 → forecast → WMO 码中文映射 → 出行建议
 * 用 FakeRestTemplate 注入固定响应，测试全程不联网（真实 HTTP 的失败路径也一并覆盖）。
 */
class HttpWeatherServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 只按 URL 关键字返回预置报文，异常场景通过 fail 开关触发 */
    private static class FakeRestTemplate extends RestTemplate {
        private final String geoBody;
        private final String forecastBody;
        private boolean fail;

        FakeRestTemplate(String geoBody, String forecastBody) {
            this.geoBody = geoBody;
            this.forecastBody = forecastBody;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getForObject(String url, Class<T> responseType, Object... uriVariables) {
            if (fail) {
                throw new RestClientException("connect timed out");
            }
            return (T) (url.contains("geocoding") ? geoBody : forecastBody);
        }
    }

    private static String geo(String name, double lat, double lon) {
        return "{\"results\":[{\"name\":\"" + name + "\",\"latitude\":" + lat + ",\"longitude\":" + lon + "}]}";
    }

    private static final String GEO_BEIJING = geo("北京", 39.9, 116.4);

    /** 三天 daily + 实况；code/min/max/current 由调用方定制 */
    private static String forecast(int currentCode, String currentTemp,
                                   int[] codes, String[] mins, String[] maxs, String[] days) {
        StringBuilder sb = new StringBuilder("{\"current\":{\"weather_code\":").append(currentCode)
                .append(",\"temperature_2m\":\"").append(currentTemp).append("\"},")
                .append("\"daily\":{\"time\":[");
        for (int i = 0; i < days.length; i++) {
            sb.append(i == 0 ? "" : ",").append("\"").append(days[i]).append("\"");
        }
        sb.append("],\"weather_code\":[");
        for (int i = 0; i < codes.length; i++) {
            sb.append(i == 0 ? "" : ",").append(codes[i]);
        }
        sb.append("],\"temperature_2m_min\":[");
        for (int i = 0; i < mins.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(mins[i]).append('"');
        }
        sb.append("],\"temperature_2m_max\":[");
        for (int i = 0; i < maxs.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(maxs[i]).append('"');
        }
        return sb.append("]}}").toString();
    }

    private HttpWeatherService service(FakeRestTemplate rt) {
        return new HttpWeatherService(rt, MAPPER);
    }

    @Test
    void todayUsesRealtimeTempAndDailyRange() {
        // 今天：实况 20.4℃ + 今日 12~22℃，天气码 61 = 小雨
        FakeRestTemplate rt = new FakeRestTemplate(GEO_BEIJING, forecast(
                61, "20.4", new int[]{61, 0, 0}, new String[]{"12", "8", "9"}, new String[]{"22", "18", "19"},
                new String[]{"2026-11-04", "2026-11-05", "2026-11-06"}));
        WeatherVO vo = service(rt).query("北京");

        assertEquals("北京", vo.getCity());
        assertEquals("小雨", vo.getWeather());
        assertEquals("20.4℃（今日 12~22℃）", vo.getTemperature());
        assertTrue(vo.getAdvice().contains("降水"), vo.getAdvice());
    }

    @Test
    void futureDatePicksMatchingDailyIndex() {
        // 目标日 2026-11-06 是 daily 的第 3 项：小雪、-3~2℃（无实况气温，取中值 -0.5℃）
        FakeRestTemplate rt = new FakeRestTemplate(GEO_BEIJING, forecast(
                0, "20.4", new int[]{0, 0, 71}, new String[]{"12", "8", "-3"}, new String[]{"22", "18", "2"},
                new String[]{"2026-11-04", "2026-11-05", "2026-11-06"}));
        WeatherVO vo = service(rt).query("北京", LocalDate.of(2026, 11, 6));

        assertEquals("小雪", vo.getWeather());
        assertEquals("-3~2℃", vo.getTemperature());
        assertTrue(vo.getAdvice().contains("降雪"), vo.getAdvice());
        assertTrue(vo.getAdvice().contains("保暖"), vo.getAdvice());
    }

    @Test
    void dateOutsideForecastWindowFallsBackToToday() {
        FakeRestTemplate rt = new FakeRestTemplate(GEO_BEIJING, forecast(
                3, "10", new int[]{3, 3, 3}, new String[]{"1", "2", "3"}, new String[]{"5", "6", "7"},
                new String[]{"2026-11-04", "2026-11-05", "2026-11-06"}));
        // 2030 年不在 7 天窗口内 → 退回今天（实况 + 今日高低温）
        WeatherVO vo = service(rt).query("北京", LocalDate.of(2030, 1, 1));

        assertEquals("阴", vo.getWeather());
        assertEquals("10℃（今日 1~5℃）", vo.getTemperature());
    }

    @Test
    void unknownCityReturnsUnavailableInsteadOfFakeWeather() {
        FakeRestTemplate rt = new FakeRestTemplate("{\"results\":[]}", "{}");
        WeatherVO vo = service(rt).query("不存在市");

        assertEquals("不存在市", vo.getCity());
        assertEquals("暂不可用", vo.getWeather());
        assertEquals("--", vo.getTemperature());
        assertTrue(vo.getAdvice().contains("获取失败"), vo.getAdvice());
    }

    @Test
    void httpFailureReturnsHonestFallbackNotMockData() {
        FakeRestTemplate rt = new FakeRestTemplate(GEO_BEIJING, "{}");
        rt.fail = true;
        WeatherVO vo = service(rt).query("北京");

        // 绝不回落成「晴 20℃」这类模拟数据
        assertEquals("暂不可用", vo.getWeather());
        assertNotEquals("晴", vo.getWeather());
        assertEquals("--", vo.getTemperature());
    }

    @Test
    void unknownWmoCodeFallsBackToUnknown() {
        // 42 不在 WMO 表内；current 缺失时沿用 daily 码
        String body = "{\"daily\":{\"time\":[\"2026-11-04\"],\"weather_code\":[42],"
                + "\"temperature_2m_min\":[\"5\"],\"temperature_2m_max\":[\"9\"]}}";
        WeatherVO vo = service(new FakeRestTemplate(GEO_BEIJING, body)).query("北京");

        assertEquals("未知", vo.getWeather());
        assertEquals("--℃（今日 5~9℃）", vo.getTemperature());
        // 既非降水也非雪雾晴 → 走通用兜底建议
        assertEquals("出行请留意实时天气变化。", vo.getAdvice());
    }

    @Test
    void stormAdviceWarnsLightning() {
        FakeRestTemplate rt = new FakeRestTemplate(GEO_BEIJING, forecast(
                95, "26", new int[]{95}, new String[]{"24"}, new String[]{"30"}, new String[]{"2026-11-04"}));
        WeatherVO vo = service(rt).query("北京");

        assertEquals("雷阵雨", vo.getWeather());
        assertTrue(vo.getAdvice().contains("雷暴"), vo.getAdvice());
    }

    @Test
    void fogAdviceWarnsVisibility() {
        FakeRestTemplate rt = new FakeRestTemplate(GEO_BEIJING, forecast(
                45, "6", new int[]{45}, new String[]{"4"}, new String[]{"9"}, new String[]{"2026-11-04"}));
        WeatherVO vo = service(rt).query("北京");

        assertEquals("雾", vo.getWeather());
        assertTrue(vo.getAdvice().contains("能见度"), vo.getAdvice());
    }

    @Test
    void hotWeatherAddsHeatAdvice() {
        FakeRestTemplate rt = new FakeRestTemplate(GEO_BEIJING, forecast(
                0, "35.5", new int[]{0}, new String[]{"28"}, new String[]{"37"}, new String[]{"2026-11-04"}));
        WeatherVO vo = service(rt).query("北京");

        assertEquals("晴", vo.getWeather());
        assertTrue(vo.getAdvice().contains("天气较好"), vo.getAdvice());
        assertTrue(vo.getAdvice().contains("防暑"), vo.getAdvice());
    }

    @Test
    void missingDailyArraysDoNotThrow() {
        // forecast 返回空对象：所有 path 走默认值，仍要给出结构完整的 VO
        WeatherVO vo = service(new FakeRestTemplate(GEO_BEIJING, "{}")).query("北京");

        assertNotNull(vo);
        assertEquals("北京", vo.getCity());
        assertNotNull(vo.getWeather());
        assertNotNull(vo.getTemperature());
        assertNotNull(vo.getAdvice());
    }
}
