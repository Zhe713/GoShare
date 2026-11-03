package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.llm.LunarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 节日倒计时工具：春节/端午/中秋/元宵/重阳/七夕/腊八 距离天数（离线确定性历法），杜绝编造节日日期。
 * 历史教训：模型曾把 2027 年春节编造成 2月1日（实为 2027-02-06）。
 */
@Component
@RequiredArgsConstructor
public class FestivalCountdownTool implements AssistantTool {

    private final LunarService lunarService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "get_festival_countdown";
    }

    @Override
    public String description() {
        return "查询距离下一个农历传统节日还有多少天，并给出节日公历日期。festival 取值：春节（缺省）、端午节、中秋节、元宵节、重阳节、七夕节、腊八节。当用户问春节/过年/除夕/端午/中秋等节日日期或倒计时时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("festival",
                        Map.of("type", "string",
                                "description", "节日名：春节/端午节/中秋节/元宵节/重阳节/七夕节/腊八节，缺省为春节")),
                "required", new String[]{});
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String festival = args.path("festival").asText("春节");
            LocalDate today = LocalDate.now();
            return switch (festival) {
                case "端午节" -> lunarService.festivalText(today, "端午节", 5, 5);
                case "中秋节" -> lunarService.festivalText(today, "中秋节", 8, 15);
                case "元宵节" -> lunarService.festivalText(today, "元宵节", 1, 15);
                case "重阳节" -> lunarService.festivalText(today, "重阳节", 9, 9);
                case "七夕节" -> lunarService.festivalText(today, "七夕节", 7, 7);
                case "腊八节" -> lunarService.festivalText(today, "腊八节", 12, 8);
                default -> lunarService.springFestivalText(today);
            };
        } catch (Exception e) {
            return "节日查询参数解析失败：" + e.getMessage();
        }
    }
}
