package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.llm.LunarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 二十四节气工具：指定节气的公历日期（离线确定性历法），杜绝编造节气日期。
 */
@Component
@RequiredArgsConstructor
public class JieQiTool implements AssistantTool {

    private final LunarService lunarService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_jieqi_date";
    }

    @Override
    public String description() {
        return "查询二十四节气（立春、雨水、惊蛰、春分、清明、谷雨、立夏、小满、芒种、夏至、小暑、大暑、立秋、处暑、白露、秋分、寒露、霜降、立冬、小雪、大雪、冬至、小寒、大寒）对应的公历日期。当用户问节气是哪天时调用。参数：jieqi 节气名（必填）。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("jieqi",
                        Map.of("type", "string", "description", "节气名，如：立春、冬至、清明")),
                "required", new String[]{"jieqi"});
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String jieqi = args.path("jieqi").asText("");
            if (jieqi.isBlank()) {
                return "请告诉我具体哪个节气（如立春、冬至、清明）。";
            }
            String text = lunarService.jieQiText(LocalDate.now(), jieqi);
            return text == null ? "这个节气暂未查询到公历日期，请换个节气名试试。" : text;
        } catch (Exception e) {
            return "节气查询参数解析失败：" + e.getMessage();
        }
    }
}
