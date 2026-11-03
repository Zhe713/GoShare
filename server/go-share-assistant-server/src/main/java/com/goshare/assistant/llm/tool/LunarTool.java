package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.llm.LunarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 农历查询工具：公历日期 → 真实农历（离线确定性历法，零外部依赖），杜绝农历幻觉。
 */
@Component
@RequiredArgsConstructor
public class LunarTool implements AssistantTool {

    private final LunarService lunarService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_lunar_date";
    }

    @Override
    public String description() {
        return "查询指定公历日期对应的农历（阴历）日期、干支纪年与生肖。当用户询问农历、阴历、老黄历、几月初几时调用。参数：date 公历日期（格式 yyyy-MM-dd，可选，缺省为今天）。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("date",
                        Map.of("type", "string", "description", "公历日期，格式 yyyy-MM-dd，可选，缺省今天")),
                "required", new String[]{});
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            LocalDate date = LocalDate.now();
            JsonNode args = objectMapper.readTree(argumentsJson);
            String dateStr = args.path("date").asText(null);
            if (dateStr != null && !dateStr.isBlank()) {
                date = LocalDate.parse(dateStr);
            }
            String text = lunarService.lunarText(date);
            return text == null ? "农历数据暂时不可用。" : text;
        } catch (Exception e) {
            return "农历查询参数解析失败：" + e.getMessage();
        }
    }
}
