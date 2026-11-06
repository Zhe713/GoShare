package com.goshare.notify.controller;

import com.goshare.common.result.Result;
import com.goshare.notify.dto.WeatherVO;
import com.goshare.notify.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 通知接口：天气查询（体验功能，可插拔数据源）、发车提醒
 */
@Slf4j
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotifyController {

    private final WeatherService weatherService;
    private final RestTemplate restTemplate;

    @Value("${goshare.notify.order-service-url}")
    private String orderUrl;

    @Value("${goshare.notify.trip-service-url}")
    private String tripUrl;

    /** 天气查询（发车前提醒/行程须知卡片的数据源；date 可选：查指定日期天气，缺省为今天） */
    @GetMapping("/weather")
    public Result<WeatherVO> weather(@RequestParam("city") String city,
                                     @RequestParam(value = "date", required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.success(weatherService.query(city, date));
    }

    /** 发车提醒：订单行程 + 出发地天气 → 人话提醒（体验功能） */
    @GetMapping("/trip-reminder")
    public Result<Map<String, Object>> tripReminder(@RequestParam("orderNo") String orderNo) {
        try {
            // 订单 → 行程ID
            Result<Map<String, Object>> orderResp = restTemplate.exchange(
                            orderUrl + "/api/order/internal/" + orderNo, HttpMethod.GET, null,
                            new ParameterizedTypeReference<Result<Map<String, Object>>>() {
                            })
                    .getBody();
            Object tripId = orderResp != null && orderResp.getData() != null
                    ? orderResp.getData().get("tripId") : null;
            if (tripId == null) {
                return Result.error(404, "订单不存在");
            }
            // 行程 → 路线/时间
            Result<Map<String, Object>> tripResp = restTemplate.exchange(
                            tripUrl + "/api/trip/" + tripId, HttpMethod.GET, null,
                            new ParameterizedTypeReference<Result<Map<String, Object>>>() {
                            })
                    .getBody();
            Map<String, Object> trip = tripResp != null ? tripResp.getData() : null;
            if (trip == null) {
                return Result.error(500, "行程服务暂不可用");
            }
            String fromCity = String.valueOf(trip.get("fromCity"));
            String toCity = String.valueOf(trip.get("toCity"));
            String departureTime = String.valueOf(trip.get("departureTime"));
            WeatherVO weather = weatherService.query(fromCity);

            Map<String, Object> reminder = new HashMap<>();
            reminder.put("orderNo", orderNo);
            reminder.put("fromCity", fromCity);
            reminder.put("toCity", toCity);
            reminder.put("departureTime", departureTime);
            reminder.put("weather", weather.getWeather() + " " + weather.getTemperature());
            reminder.put("content", "【发车提醒】您的行程 " + fromCity + "→" + toCity
                    + " 将于 " + departureTime.replace("T", " ").substring(0, 16)
                    + " 发车。出发地天气：" + weather.getWeather() + " " + weather.getTemperature()
                    + "，" + weather.getAdvice() + " 请提前到达集合点。");
            return Result.success(reminder);
        } catch (Exception e) {
            log.warn("[notify] 发车提醒组装失败: {}", e.getMessage());
            return Result.error(500, "发车提醒暂不可用");
        }
    }
}
