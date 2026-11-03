package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.llm.TripRuleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行程查询工具：按出发地/目的地/日期调 trip 服务返回真实班次（价格/余座来自系统数据），
 * 无班次时如实告知「暂无班次」，杜绝模型编造班次时间与价格。
 */
@Component
@RequiredArgsConstructor
public class TripSearchTool implements AssistantTool {

    private final TripRuleProvider tripRuleProvider;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "search_trips";
    }

    @Override
    public String description() {
        return "查询 GoShare 平台某条城际线路的真实可预订班次、出发时间、价格与余座。当用户询问行程、班次、几点发车、某地到某地有没有车时调用。参数：fromCity 出发城市，toCity 到达城市，date 出行日期（格式 yyyy-MM-dd，可选）。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fromCity", Map.of("type", "string", "description", "出发城市，如：北京"));
        props.put("toCity", Map.of("type", "string", "description", "到达城市，如：邯郸"));
        props.put("date", Map.of("type", "string", "description", "出行日期，格式 yyyy-MM-dd，可选"));
        return Map.of("type", "object", "properties", props,
                "required", new String[]{"fromCity", "toCity"});
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String from = args.path("fromCity").asText("");
            String to = args.path("toCity").asText("");
            if (from.isBlank() || to.isBlank()) {
                return "出发地和目的地不能为空，请补充完整后再查询。";
            }
            String dateStr = args.path("date").asText(null);
            LocalDate date = dateStr != null && !dateStr.isBlank() ? LocalDate.parse(dateStr) : null;
            TripRuleProvider.TripQuery tq = new TripRuleProvider.TripQuery("fetch", from, to, date, null);
            String answer = tripRuleProvider.fetch(tq);
            return answer == null ? "行程服务暂时不可用，请稍后再试。" : answer;
        } catch (Exception e) {
            return "行程查询参数解析失败：" + e.getMessage();
        }
    }
}
