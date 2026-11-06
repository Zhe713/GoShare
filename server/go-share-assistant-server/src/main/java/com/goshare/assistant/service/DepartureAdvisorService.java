package com.goshare.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * F2 出发参谋（「我明天的车，几点出门？」）
 *
 * <p>设计（方案八 F2）：天气告诉你"下不下雨"，出发参谋告诉你"几点走"。
 * 三个真实数据源确定性合成，每个数字都可追溯到数据源，零 LLM 调用：
 * <ol>
 *   <li>订单服务：订单 → 行程ID（发车时间由行程服务给）</li>
 *   <li>行程服务：发车时间 / 出发地（上车点为行程起点站）</li>
 *   <li>notify 服务：出发地当天真实天气（Open-Meteo）</li>
 * </ol>
 * 出门时刻 = 发车时间 − 市内通勤 45min − 提前到站 15min − 恶劣天气缓冲（雨/雪/雾/雷 +10min）。
 * 天气拿不到时诚实降级（buffer=0 并注明），绝不编造天气数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepartureAdvisorService {

    /** 市内通勤预估（分钟）——演示口径固定值，可接入地图 ETA 替换 */
    private static final int COMMUTE_MINUTES = 45;
    /** 提前到达集合点的时间（分钟） */
    private static final int ARRIVE_EARLY_MINUTES = 15;
    /** 恶劣天气额外缓冲（分钟） */
    private static final int WEATHER_BUFFER_MINUTES = 10;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${goshare.assistant.order-service-url:http://go-share-order-server}")
    private String orderUrl;

    @Value("${goshare.assistant.trip-url:http://go-share-trip-server}")
    private String tripUrl;

    @Value("${goshare.assistant.notify-url:http://go-share-notify-server}")
    private String notifyUrl;

    /**
     * 出发参谋建议结果（所有数值确定性计算，可追溯）
     */
    public static class DepartureAdvice {
        public String orderNo;
        public String fromCity;
        public String toCity;
        public String departureTime;   // 发车时间（行程服务）
        public String leaveAt;         // 建议出门时刻（合成结果）
        public int commuteMinutes = COMMUTE_MINUTES;
        public int arriveEarlyMinutes = ARRIVE_EARLY_MINUTES;
        public int weatherBufferMinutes;  // 0~10，取决于天气
        public String weatherText;        // 出发地天气（notify 真实数据；拿不到为 null）
        public String weatherSource;      // 天气来源说明（诚实降级时提示）
        public String advice;             // 完整人话建议
    }

    /**
     * 按订单生成出发参谋建议。
     *
     * @param userId      当前登录用户（校验订单归属）
     * @param orderNo     订单号
     */
    public DepartureAdvice advise(Long userId, String orderNo) {
        DepartureAdvice a = new DepartureAdvice();
        a.orderNo = orderNo;

        // 1. 订单 → tripId（内部接口，带 userId 让 order 校验归属）
        JsonNode order = fetchJson(orderUrl + "/api/order/internal/" + orderNo + "?passengerId=" + userId, "订单");
        if (order == null || order.path("tripId").asLong() == 0) {
            throw new com.goshare.common.exception.BusinessException(404, "订单不存在或无权查看");
        }
        a.orderNo = order.path("orderNo").asText(orderNo);

        // 2. 行程 → 发车时间 / 出发地 / 目的地
        JsonNode trip = fetchJson(tripUrl + "/api/trip/" + order.path("tripId").asLong(), "行程");
        if (trip == null) {
            throw new com.goshare.common.exception.BusinessException(500, "行程服务暂不可用");
        }
        a.fromCity = trip.path("fromCity").asText();
        a.toCity = trip.path("toCity").asText();
        LocalDateTime departure = parseTime(trip.path("departureTime").asText(""));
        if (departure == null) {
            throw new com.goshare.common.exception.BusinessException(500, "行程发车时间解析失败");
        }
        a.departureTime = departure.format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm"));

        // 3. 出发地当天天气（真实数据；失败诚实降级不加缓冲）
        int weatherBuffer = 0;
        String weatherText = null;
        String weatherSource = "天气数据暂不可用，建议出发前自行确认（未计入额外缓冲）。";
        try {
            Map<String, String> params = new HashMap<>();
            params.put("city", a.fromCity);
            String url = notifyUrl + "/api/notify/weather?city={city}&date={date}";
            params.put("date", departure.toLocalDate().toString());
            String body = restTemplate.getForObject(url, String.class, params);
            JsonNode data = objectMapper.readTree(body).path("data");
            String w = data.path("weather").asText("");
            String t = data.path("temperature").asText("");
            if (!w.isBlank() && !"暂不可用".equals(w)) {
                weatherText = w + " " + t;
                weatherSource = "来自 Open-Meteo 实时预报";
                if (BAD_WEATHER.matcher(w).find()) {
                    weatherBuffer = WEATHER_BUFFER_MINUTES;
                }
            }
        } catch (Exception e) {
            log.warn("[departure-advisor] 天气查询失败 from={}: {}", a.fromCity, e.getMessage());
        }
        a.weatherText = weatherText;
        a.weatherSource = weatherSource;
        a.weatherBufferMinutes = weatherBuffer;

        // 4. 确定性合成：出门时刻 = 发车 − 通勤 − 提前到站 − 天气缓冲
        LocalDateTime leaveAt = departure
                .minusMinutes(COMMUTE_MINUTES)
                .minusMinutes(ARRIVE_EARLY_MINUTES)
                .minusMinutes(weatherBuffer);
        a.leaveAt = leaveAt.format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm"));

        // 5. 人话建议（全部数字可追溯）
        StringBuilder sb = new StringBuilder();
        sb.append("建议 ").append(a.leaveAt).append(" 出门：市内通勤约 ").append(COMMUTE_MINUTES)
                .append(" 分钟，提前 ").append(ARRIVE_EARLY_MINUTES).append(" 分钟到上车点");
        if (weatherBuffer > 0) {
            sb.append("；出发地").append(extractWeatherWord(a.weatherText))
                    .append("，再加 ").append(weatherBuffer).append(" 分钟缓冲");
        }
        sb.append("。");
        if (weatherText != null) {
            sb.append("出发地天气：").append(weatherText).append("（").append(weatherSource).append("）。");
        } else {
            sb.append(weatherSource);
        }
        long until = Duration.between(LocalDateTime.now(), departure).toMinutes();
        if (until < 0) {
            sb.append("注意：该行程已发车。");
        } else if (until < COMMUTE_MINUTES + ARRIVE_EARLY_MINUTES) {
            sb.append("注意：距发车仅剩 ").append(until).append(" 分钟，请立即出发！");
        }
        a.advice = sb.toString();
        log.info("[departure-advisor] 建议已生成 orderNo={} leaveAt={} buffer={}", orderNo, a.leaveAt, weatherBuffer);
        return a;
    }

    /** 恶劣天气关键词（雨/雪/雾/雷/冻/雹/沙尘） */
    private static final java.util.regex.Pattern BAD_WEATHER =
            java.util.regex.Pattern.compile("雨|雪|雾|雷|冻|雹|沙尘");

    /** 从天气文本提取主天气词（"小雨 12~18℃" → "小雨"） */
    private String extractWeatherWord(String weatherText) {
        if (weatherText == null) {
            return "恶劣天气";
        }
        var m = java.util.regex.Pattern.compile("[\\u4e00-\u9fa5]+").matcher(weatherText);
        return m.find() ? m.group() : "恶劣天气";
    }

    /** GET JSON 并取 data 节点；失败返回 null（由调用方决定降级文案） */
    private JsonNode fetchJson(String url, String what) {
        try {
            var resp = restTemplate.exchange(url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<com.goshare.common.result.Result<Object>>() {
                    }).getBody();
            if (resp == null || resp.getCode() != 200 || resp.getData() == null) {
                return null;
            }
            return objectMapper.valueToTree(resp.getData());
        } catch (Exception e) {
            log.warn("[departure-advisor] {} 获取失败 url={}: {}", what, url, e.getMessage());
            return null;
        }
    }

    private LocalDateTime parseTime(String s) {
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
