package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.llm.LunarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节假日出行日历工具（F6：把"黄历工具"救回来）。
 *
 * <p>产品逻辑（方案八 F6）：春节/清明/五一/国庆是顺风车全年最大的出行高峰，
 * 距离节日的天数是离线确定性历法（lunar-java，0 token），话术只用确定性事实：
 * 「距春节还有 N 天，节日前后为出行高峰，建议提前发布/预订行程」。
 * 不编造"往年加价 30%"这类无数据支撑的数字——按项目防幻觉原则，没有统计就不说数。
 */
@Component
@RequiredArgsConstructor
public class HolidayTravelTool implements AssistantTool {

    private final LunarService lunarService;
    private final ObjectMapper objectMapper;

    /** 公历固定节日（出行高峰）：清明/五一/国庆 */
    private static final Map<String, int[]> SOLAR_FESTIVALS = Map.of(
            "清明节", new int[]{4, 4},
            "劳动节", new int[]{5, 1},
            "国庆节", new int[]{10, 1}
    );

    /** 农历节日：春节/端午/中秋 */
    private static final Map<String, int[]> LUNAR_FESTIVALS = Map.of(
            "春节", new int[]{1, 1},
            "端午节", new int[]{5, 5},
            "中秋节", new int[]{8, 15}
    );

    @Override
    public String name() {
        return "get_holiday_travel_advice";
    }

    @Override
    public String description() {
        return "查询距离下一个出行高峰节日（春节/清明/五一/国庆/端午/中秋）还有多少天，并给出确定性出行建议（节前是否该提前发布或预订行程）。当用户问节日出行、节前要不要提前订票、节假日拼车高峰时调用。festival 缺省返回距离最近的下一个高峰节日。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("festival", Map.of("type", "string",
                "description", "节日名：春节/清明节/劳动节/国庆节/端午节/中秋节，缺省返回下一个即将到来的高峰节日"));
        return Map.of("type", "object", "properties", props, "required", new String[]{});
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String festival = args.path("festival").asText("").trim();
            LocalDate today = LocalDate.now();
            if (festival.isBlank()) {
                return nearestFestivalAdvice(today);
            }
            int[] md = LUNAR_FESTIVALS.containsKey(festival) ? LUNAR_FESTIVALS.get(festival)
                    : SOLAR_FESTIVALS.get(festival);
            if (md == null) {
                return "暂不支持查询「" + festival + "」的出行高峰建议。支持：春节/清明节/劳动节/国庆节/端午节/中秋节。";
            }
            return adviceFor(today, festival, md);
        } catch (Exception e) {
            return "节假日出行查询失败：" + e.getMessage();
        }
    }

    /** 缺省：取下一个到来的高峰节日 */
    private String nearestFestivalAdvice(LocalDate today) {
        String bestName = null;
        LocalDate bestDate = null;
        for (var e : SOLAR_FESTIVALS.entrySet()) {
            LocalDate d = nextSolarFestival(today, e.getValue()[0], e.getValue()[1]);
            if (d != null && (bestDate == null || d.isBefore(bestDate))) {
                bestName = e.getKey();
                bestDate = d;
            }
        }
        for (var e : LUNAR_FESTIVALS.entrySet()) {
            LocalDate d = lunarService.nextFestivalDate(today, e.getValue()[0], e.getValue()[1]);
            if (d != null && (bestDate == null || d.isBefore(bestDate))) {
                bestName = e.getKey();
                bestDate = d;
            }
        }
        if (bestName == null) {
            return "节日历法计算暂不可用，请稍后再试。";
        }
        return lunarService.holidayTravelText(today, bestName, bestDate);
    }

    private String adviceFor(LocalDate today, String festival, int[] md) {
        if (LUNAR_FESTIVALS.containsKey(festival)) {
            LocalDate date = lunarService.nextFestivalDate(today, md[0], md[1]);
            return date == null ? "节日历法计算暂不可用，请稍后再试。"
                    : lunarService.holidayTravelText(today, festival, date);
        }
        return lunarService.solarHolidayTravelText(today, festival, md[0], md[1]);
    }

    /** 下一个公历节日（含当年，若已过则取明年） */
    private LocalDate nextSolarFestival(LocalDate today, int month, int day) {
        LocalDate d = LocalDate.of(today.getYear(), month, day);
        if (d.isBefore(today)) {
            d = LocalDate.of(today.getYear() + 1, month, day);
        }
        return d;
    }
}
