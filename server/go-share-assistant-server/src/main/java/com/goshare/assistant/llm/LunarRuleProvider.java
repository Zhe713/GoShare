package com.goshare.assistant.llm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * 历法规则关卡（离线确定性计算·零模型调用）：
 * 农历/阴历/黄历 → 今日农历；春节/过年/除夕 → 春节倒计时；
 * 端午/中秋/元宵/重阳/七夕 → 节日倒计时；今年是什么年/属相 → 干支纪年；
 * 立春/冬至等二十四节气 → 节气公历日期。
 * 覆盖「确定性历法知识」全部分支，杜绝模型凭记忆编造（历史教训：2027 春节被模型答成 2月1日，实为 2027-02-06）。
 */
@Component
@RequiredArgsConstructor
public class LunarRuleProvider {

    /** 春节/过年意图（「离过年还有几天」「春节是哪天」「除夕」） */
    private static final Pattern SPRING_FESTIVAL = Pattern.compile("春节|过年|除夕|年三十|年夜饭|还有几天到");

    /** 农历传统节日意图 */
    private static final Pattern FESTIVAL = Pattern.compile("端午|中秋|重阳|元宵|七夕|腊八");

    /** 干支纪年/生肖意图（「今年是什么年」「属什么」） */
    private static final Pattern YEAR = Pattern.compile("今年是什么年|今年是.*年|属什么|属相|生肖|什么属相");

    /** 二十四节气意图 */
    private static final Pattern JIE_QI = Pattern.compile(
            "立春|雨水|惊蛰|春分|清明|谷雨|立夏|小满|芒种|夏至|小暑|大暑|立秋|处暑|白露|秋分|寒露|霜降|立冬|小雪|大雪|冬至|小寒|大寒|节气");

    /** 农历日期意图（避免误吞「农历年前后优惠」等非历法问法；「农历/黄历」后紧跟「年」不命中） */
    private static final Pattern LUNAR = Pattern.compile("农历(?!年)|阴历|老黄历|黄历(?!年)|几月初几");

    private final LunarService lunarService;

    /**
     * 历法问句返回确定性答案，非历法问句返回 null（交由后续关卡/工具通道处理）。
     */
    public String maybeAnswer(String question) {
        LocalDate today = LocalDate.now();
        if (SPRING_FESTIVAL.matcher(question).find()) {
            return lunarService.springFestivalText(today);
        }
        if (FESTIVAL.matcher(question).find()) {
            Festival festival = festivalOf(question);
            if (festival != null) {
                return lunarService.festivalText(today, festival.name(), festival.month(), festival.day());
            }
        }
        if (YEAR.matcher(question).find()) {
            return lunarService.yearText(today);
        }
        if (JIE_QI.matcher(question).find()) {
            return lunarService.jieQiText(today, question);
        }
        if (LUNAR.matcher(question).find()) {
            String text = lunarService.lunarText(today);
            return text == null ? null : "今天是" + text + "。";
        }
        return null;
    }

    /** 命中节日时返回节日名与农历月日；未识别返回 null */
    private Festival festivalOf(String question) {
        if (question.contains("端午")) {
            return new Festival("端午节", 5, 5);
        }
        if (question.contains("中秋")) {
            return new Festival("中秋节", 8, 15);
        }
        if (question.contains("元宵")) {
            return new Festival("元宵节", 1, 15);
        }
        if (question.contains("重阳")) {
            return new Festival("重阳节", 9, 9);
        }
        if (question.contains("七夕")) {
            return new Festival("七夕节", 7, 7);
        }
        if (question.contains("腊八")) {
            return new Festival("腊八节", 12, 8);
        }
        return null;
    }

    private record Festival(String name, int month, int day) {
    }
}
