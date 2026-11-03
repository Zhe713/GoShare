package com.goshare.assistant.llm;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户口语日期解析（本地、无外部依赖）：
 * 支持「明天/后天/大后天」「X月X日/X月X号」「X.X / X.X号」「X号/日（本月）」，
 * 解析失败返回 null（由调用方按「今天」处理）。
 */
final class DateParser {

    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");
    private static final Pattern DOT_DAY = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})(?:号|日)?");
    private static final Pattern PLAIN_DAY = Pattern.compile("(\\d{1,2})[号日](?!\\d)");

    private DateParser() {
    }

    /** 解析口语日期；未识别返回 null（视为今天） */
    static LocalDate parse(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        LocalDate now = LocalDate.now();
        if (question.contains("大后天")) {
            return now.plusDays(3);
        }
        if (question.contains("后天")) {
            return now.plusDays(2);
        }
        if (question.contains("明天")) {
            return now.plusDays(1);
        }
        Matcher m = MONTH_DAY.matcher(question);
        if (m.find()) {
            try {
                return LocalDate.of(now.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            } catch (DateTimeException e) {
                return null;
            }
        }
        m = DOT_DAY.matcher(question);
        if (m.find()) {
            try {
                return LocalDate.of(now.getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            } catch (DateTimeException e) {
                return null;
            }
        }
        m = PLAIN_DAY.matcher(question);
        if (m.find()) {
            try {
                return LocalDate.of(now.getYear(), now.getMonthValue(), Integer.parseInt(m.group(1)));
            } catch (DateTimeException e) {
                return null;
            }
        }
        return null;
    }

    /** 日期人话标签：今天→「今日」，未来→「11月4日」 */
    static String label(LocalDate date) {
        if (date == null || date.equals(LocalDate.now())) {
            return "今日";
        }
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }
}
