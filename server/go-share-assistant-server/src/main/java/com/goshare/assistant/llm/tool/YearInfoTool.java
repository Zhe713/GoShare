package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.llm.LunarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 干支纪年工具：今年是什么年/属相（离线确定性历法）。
 */
@Component
@RequiredArgsConstructor
public class YearInfoTool implements AssistantTool {

    private final LunarService lunarService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_year_info";
    }

    @Override
    public String description() {
        return "查询今年的干支纪年与生肖，以及今天的农历日期。当用户问今年是什么年、属什么、生肖、属相时调用。无需参数。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", new String[]{});
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            String text = lunarService.yearText(LocalDate.now());
            return text == null ? "历法数据暂时不可用。" : text;
        } catch (Exception e) {
            return "干支年查询失败：" + e.getMessage();
        }
    }
}
