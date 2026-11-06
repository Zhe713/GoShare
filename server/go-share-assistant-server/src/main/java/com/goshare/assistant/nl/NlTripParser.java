package com.goshare.assistant.nl;

import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然语言行程查询解析器（规则模板为主，可插拔 LLM 为辅）
 * <p>
 * 设计约束：解析只输出结构化条件，不生成行程列表；列表一律由 ES 查询 + 特征排序代码产出（方案约定：A1 列表不靠 LLM 生成）。
 * <p>
 * 支持句式：
 * - 路线："周五18点从石家庄去邯郸"、"去北京"、"回石家庄"
 * - 时间："明天上午"、"周五18点"、"后天下午"、"晚上8点"
 * - 偏好："能带大件"、"便宜点的"、"离地铁近"（偏好词仅记录，排序加权）
 */
@Slf4j
public final class NlTripParser {

    /** 演示城市词典（可扩充） */
    private static final List<String> CITIES = List.of(
            "石家庄", "邯郸", "北京", "天津", "保定", "邢台", "唐山", "廊坊",
            "衡水", "沧州", "秦皇岛", "承德", "张家口", "上海", "广州", "深圳");

    /** 路线句式：从X(间插少量字)到Y —— 口语常为「从石家庄订明天去邯郸」，不要求紧邻 */
    private static final Pattern ROUTE = Pattern.compile(
            "从\\s*([\\u4e00-\u9fa5]{2,6})[\\u4e00-\u9fa5]{0,3}?(?:到|去|至|回)\\s*([\\u4e00-\u9fa5]{2,6})");
    private static final Pattern FROM = Pattern.compile("从\\s*([\\u4e00-\u9fa5]{2,6})");
    private static final Pattern GO_TO = Pattern.compile("(?:去|到|前往|至|回)\\s*([\\u4e00-\u9fa5]{2,6})");

    /** 日期句式：今天/明天/后天/周X/本周X */
    private static final Pattern REL_DAY = Pattern.compile("(今天|明天|后天)");
    private static final Pattern WEEKDAY = Pattern.compile("(?:本周|周|星期)([一二三四五六日天])");

    /** 时段句式：上午/中午/下午/晚上 + 具体点 */
    private static final Pattern HOUR = Pattern.compile("(\\d{1,2})\\s*(?:点|时)(?:半)?");
    private static final Pattern SESSION = Pattern.compile("(上午|中午|下午|晚上|早上)");

    /** 偏好句式 */
    private static final Pattern LUGGAGE = Pattern.compile("(大件|大行李箱|行李)");
    private static final Pattern CHEAP = Pattern.compile("(便宜|划算|性价比|省钱)");

    /** 人数句式（F1 对话式下单）："两个人"/"两人"/"2个人"/"三个座位"/"3人" */
    private static final Pattern SEAT_COUNT = Pattern.compile(
            "([一二两三四五六七八九\\d])\\s*(?:个|位)?\\s*(?:人|座|座位)");
    private static final Map<String, Integer> CN_NUM = Map.of(
            "一", 1, "两", 2, "二", 2, "三", 3, "四", 4, "五", 5, "六", 6, "七", 7, "八", 8, "九", 9);

    private NlTripParser() {
    }

    /**
     * 解析自然语言为结构化查询（无法识别任何条件时返回空对象）
     */
    public static NlTripQuery parse(String text) {
        NlTripQuery query = new NlTripQuery();
        if (text == null || text.isBlank()) {
            return query;
        }
        String t = text.trim();

        // 1. 路线（优先整句 ROUTE；失败时用独立 FROM/TO 兜底；
        //    捕获串经 matchCity 做词典前缀截断，容忍「去邯郸的」「石家庄订」等口语后缀）
        Matcher route = ROUTE.matcher(t);
        String from = null;
        String to = null;
        if (route.find()) {
            from = matchCity(route.group(1));
            to = matchCity(route.group(2));
        }
        if (from == null) {
            Matcher f = FROM.matcher(t);
            if (f.find()) {
                from = matchCity(f.group(1));
            }
        }
        if (to == null) {
            Matcher g = GO_TO.matcher(t);
            if (g.find()) {
                to = matchCity(g.group(1));
            }
        }
        if (from != null) {
            query.setFromCity(from);
        }
        if (to != null) {
            query.setToCity(to);
        }

        // 2. 日期（基于今天）
        LocalDate today = LocalDate.now();
        Matcher rel = REL_DAY.matcher(t);
        if (rel.find()) {
            switch (rel.group(1)) {
                case "今天" -> query.setDepartDate(today);
                case "明天" -> query.setDepartDate(today.plusDays(1));
                case "后天" -> query.setDepartDate(today.plusDays(2));
                default -> {
                }
            }
        } else {
            Matcher wd = WEEKDAY.matcher(t);
            if (wd.find()) {
                DayOfWeek target = parseWeekday(wd.group(1));
                if (target != null) {
                    LocalDate date = today;
                    while (date.getDayOfWeek() != target) {
                        date = date.plusDays(1);
                    }
                    query.setDepartDate(date);
                }
            }
        }

        // 3. 时间段
        Matcher hour = HOUR.matcher(t);
        if (hour.find()) {
            int h = Integer.parseInt(hour.group(1));
            if (h <= 24) {
                query.setHourPrefer(h);
            }
        } else {
            Matcher session = SESSION.matcher(t);
            if (session.find()) {
                switch (session.group(1)) {
                    case "早上", "上午" -> query.setHourPrefer(9);
                    case "中午" -> query.setHourPrefer(12);
                    case "下午" -> query.setHourPrefer(15);
                    case "晚上" -> query.setHourPrefer(20);
                    default -> {
                    }
                }
            }
        }

        // 4. 偏好
        if (LUGGAGE.matcher(t).find()) {
            query.setLargeLuggage(Boolean.TRUE);
        }
        if (CHEAP.matcher(t).find()) {
            query.setPreferCheap(Boolean.TRUE);
        }

        // 5. 人数（F1 对话式下单；1~6 人，超出按 6 截断）
        Matcher seat = SEAT_COUNT.matcher(t);
        if (seat.find()) {
            String n = seat.group(1);
            int count = CN_NUM.containsKey(n) ? CN_NUM.get(n) : Integer.parseInt(n);
            if (count >= 1) {
                query.setSeatCount(Math.min(count, 6));
            }
        }

        log.info("[nl-parser] 输入={} 结构化={}", t, query);
        return query;
    }

    private static DayOfWeek parseWeekday(String w) {
        return switch (w) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            case "日", "天" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /**
     * 词典前缀截断匹配：口语捕获串常带后缀（「去邯郸的」「石家庄订」），
     * 从 4 字到 2 字逐级取前缀尝试命中城市词典。
     */
    private static String matchCity(String raw) {
        if (raw == null) {
            return null;
        }
        for (int len = Math.min(raw.length(), 4); len >= 2; len--) {
            String candidate = raw.substring(0, len);
            if (CITIES.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
