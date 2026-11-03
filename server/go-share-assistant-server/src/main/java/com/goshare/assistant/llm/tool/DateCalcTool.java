package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 公历日期计算工具：指定日期的星期几 + 距今天数（确定性计算），杜绝模型口头算术（历史教训：
 * 模型把 2026-12-15 算成周一，实为周二）。当用户问某天是星期几、距离某日期还有多少天时调用。
 */
@Component
@RequiredArgsConstructor
public class DateCalcTool implements AssistantTool {

    private static final String[] WEEK_CN = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_date_calc";
    }

    @Override
    public String description() {
        return "计算指定公历日期的星期几，以及今天到该日期相隔多少天。当用户问某天是星期几、距离某日期还有多少天/几天时调用。参数：date 公历日期（格式 yyyy-MM-dd，必填）。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("date",
                        Map.of("type", "string", "description", "公历日期，格式 yyyy-MM-dd")),
                "required", new String[]{"date"});
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String dateStr = args.path("date").asText("");
            LocalDate target = LocalDate.parse(dateStr);
            LocalDate today = LocalDate.now();
            DayOfWeek dow = target.getDayOfWeek();
            long days = ChronoUnit.DAYS.between(today, target);
            return target.getYear() + "年" + target.getMonthValue() + "月" + target.getDayOfMonth()
                    + "日，" + WEEK_CN[dow.getValue() - 1] + "。（距今天还有 " + days + " 天）";
        } catch (Exception e) {
            return "日期计算参数解析失败：" + e.getMessage() + "（请传 yyyy-MM-dd 格式的公历日期）";
        }
    }
}
