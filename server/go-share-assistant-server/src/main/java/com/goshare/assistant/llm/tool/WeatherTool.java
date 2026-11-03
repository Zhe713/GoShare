package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.llm.WeatherRuleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 天气查询工具：按城市/日期调 notify 天气服务返回真实预报；接口失败如实告知，不编造。
 */
@Component
@RequiredArgsConstructor
public class WeatherTool implements AssistantTool {

    private final WeatherRuleProvider weatherRuleProvider;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_weather";
    }

    @Override
    public String description() {
        return "查询指定城市指定日期的真实天气预报（支持未来 7 天）。当用户询问天气、温度、下雨、冷不冷、热不热、要不要带伞时调用。参数：city 城市名，date 日期（格式 yyyy-MM-dd，可选，缺省为今天）。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("city", Map.of("type", "string", "description", "城市名，如：邯郸"));
        props.put("date", Map.of("type", "string", "description", "日期，格式 yyyy-MM-dd，可选，缺省今天"));
        return Map.of("type", "object", "properties", props,
                "required", new String[]{"city"});
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String city = args.path("city").asText("");
            if (city.isBlank()) {
                return "城市不能为空，请补充城市名。";
            }
            String dateStr = args.path("date").asText(null);
            LocalDate date = dateStr != null && !dateStr.isBlank() ? LocalDate.parse(dateStr) : null;
            String answer = weatherRuleProvider.fetch(new WeatherRuleProvider.WeatherQuery(city, date));
            return answer == null ? "天气服务暂时不可用，请稍后再试。" : answer;
        } catch (Exception e) {
            return "天气查询参数解析失败：" + e.getMessage();
        }
    }
}
