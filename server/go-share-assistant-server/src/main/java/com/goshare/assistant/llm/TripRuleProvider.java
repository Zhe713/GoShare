package com.goshare.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行程意图规则提供器（真实数据·零模型调用，杜绝行程幻觉）：
 * 识别「北京到邯郸有车吗/我想去邯郸/从邯郸出发/11.3 下午出发」等行程意图，
 * 城市缺失时从多轮上下文（历史用户消息）回捞；出发地/目的地齐全即调 trip 服务
 * 返回真实班次；无班次时诚实告知「暂无班次」，绝不编造班次时间/价格。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripRuleProvider {

    /** 示例运营及周边城市 */
    private static final List<String> CITIES = List.of(
            "石家庄", "北京", "保定", "邯郸", "天津", "唐山", "廊坊", "张家口", "承德",
            "秦皇岛", "沧州", "邢台", "衡水"
    );

    /** 行程意图动词 */
    private static final Pattern VERB = Pattern.compile("去|到|回|出发|拼车|顺风|班次|行程|有车|线路|几点走|几点发|坐车");

    /** 非行程问法（即便含城市也不当行程查） */
    private static final Pattern NON_TRIP = Pattern.compile("好玩|旅游|景点|美食|怎么样|如何|是什么|介绍|攻略|天气");

    /** 显式「从X」起点 */
    private static final Pattern FROM = Pattern.compile("从([\\u4e00-\u9fa5]+)");
    /** 显式「去X/到X/回X」终点 */
    private static final Pattern TO = Pattern.compile("[去回到]([\\u4e00-\u9fa5]{2,4})");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${goshare.assistant.trip-url:http://go-share-trip-server}")
    private String tripUrl;

    /** 行程查询意图（解析结果，纯本地计算） */
    public static class TripQuery {
        /** fetch=查真实班次；clarify=缺信息反问；ask=日期-only 澄清天气/班次 */
        public final String mode;
        public final String from;
        public final String to;
        public final LocalDate date;
        public final String hint;

        public TripQuery(String mode, String from, String to, LocalDate date, String hint) {
            this.mode = mode;
            this.from = from;
            this.to = to;
            this.date = date;
            this.hint = hint;
        }
    }

    /**
     * 解析行程意图；未命中返回 null（回落规则/模型）。
     * 命中但信息不全时返回 clarify/ask 反问，比模型脑补班次更诚实。
     */
    public TripQuery resolve(String question, List<Map<String, String>> history) {
        LocalDate date = DateParser.parse(question);
        List<String> citiesInQ = citiesIn(question);
        boolean verb = VERB.matcher(question).find();

        // 非行程问法（旅游/介绍/天气等）不拦截
        if (NON_TRIP.matcher(question).find()) {
            return null;
        }

        // 日期-only 追问（如「11.4号的呢」）：分不清是天气还是班次，诚实反问
        if (date != null && !verb && citiesInQ.isEmpty() && question.contains("呢")) {
            return new TripQuery("ask", null, null, date,
                    "你是想问" + DateParser.label(date) + "的天气，还是" + DateParser.label(date) + "的班次呢？我可以帮你查~");
        }

        boolean tripIntent = citiesInQ.size() >= 2
                || (citiesInQ.size() == 1 && verb)
                || (citiesInQ.isEmpty() && verb && date != null && hasCityInHistory(history))
                // 裸城市名（如补发「北京」）+ 历史已有城市上下文 → 视为行程追问
                || (citiesInQ.size() == 1 && !verb && hasCityInHistory(history));
        if (!tripIntent) {
            return null;
        }

        String from = fromIn(question);
        String to = toIn(question);
        if (citiesInQ.size() >= 2) {
            // 「从X到Y / X到Y」按出现顺序：先出现为出发地
            if (from == null) {
                from = citiesInQ.get(0);
            }
            if (to == null) {
                to = citiesInQ.get(1);
            }
        }

        // 从历史用户消息回捞缺失的城市（终点优先：历史里的「去X/到X」）
        if (from == null || to == null) {
            Map<String, String> hist = cityFromHistory(history, from, to);
            if (from == null) {
                from = hist.get("from");
            }
            if (to == null) {
                to = hist.get("to");
            }
        }

        // 裸城市名 + 历史已有终点 → 该城市视为出发地（如用户补发「北京」）；
        // 城市名恰好等于终点时（如「我想去邯郸」）不反当出发地
        if (citiesInQ.size() == 1 && from == null && to != null && !citiesInQ.get(0).equals(to)
                && fromIn(question) == null && toIn(question) == null) {
            from = citiesInQ.get(0);
        }

        if (from == null && to == null) {
            return new TripQuery("clarify", null, null, date, "好的，请告诉我出发地和目的地，我来帮你查真实班次~");
        }
        if (from == null) {
            return new TripQuery("clarify", null, to, date, "好的，请问从哪个城市出发呢？");
        }
        if (to == null) {
            return new TripQuery("clarify", from, null, date, "好的，请问要去哪个城市呢？");
        }
        return new TripQuery("fetch", from, to, date, null);
    }

    /**
     * 拉取真实班次并组装人话回答；无班次时诚实告知，绝不编造。
     */
    public String fetch(TripQuery tq) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("fromCity", tq.from);
            params.put("toCity", tq.to);
            String url = tripUrl + "/api/trip/search?fromCity={fromCity}&toCity={toCity}";
            if (tq.date != null) {
                params.put("departDate", tq.date.toString());
                url += "&departDate={departDate}";
            }
            String body = restTemplate.getForObject(url, String.class, params);
            JsonNode data = objectMapper.readTree(body).path("data");
            if (!data.isArray() || data.isEmpty()) {
                String label = tq.date == null ? "" : DateParser.label(tq.date) + " ";
                return String.format("【暂无班次】%s→%s %s暂无可预订班次。建议换个日期，或看看反向线路~", tq.from, tq.to, label.trim());
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode trip : data) {
                if (lines.size() >= 3) {
                    break;
                }
                String time = trip.path("departureTime").asText("");
                String day = "";
                if (!time.isBlank()) {
                    try {
                        LocalDateTime dt = LocalDateTime.parse(time);
                        day = dt.getMonthValue() + "月" + dt.getDayOfMonth() + "日 ";
                    } catch (Exception e) {
                        day = time.substring(0, 10) + " ";
                    }
                }
                String price = trip.path("price").asText("");
                String seats = trip.path("remainSeats").asText("");
                lines.add(String.format("%s%s出发 · %s元 · 余%s座", day, time.length() > 11 ? time.substring(11, 16) : time, price, seats));
            }
            String label = tq.date == null ? "" : DateParser.label(tq.date) + " ";
            return String.format("【%s→%s %s班次】%s。可以点击对应行程下单占座~", tq.from, tq.to, label.trim(), String.join("；", lines));
        } catch (Exception e) {
            log.warn("[llm-gateway] 行程查询不可用，回落模型 from={} to={} err={}", tq.from, tq.to, e.getMessage());
            return null;
        }
    }

    private boolean hasCityInHistory(List<Map<String, String>> history) {
        return history != null && history.stream().anyMatch(h -> {
            String content = h.getOrDefault("content", "");
            return citiesIn(content).size() > 0;
        });
    }

    private List<String> citiesIn(String text) {
        List<String> found = new ArrayList<>();
        if (text == null) {
            return found;
        }
        for (String city : CITIES) {
            if (text.contains(city)) {
                found.add(city);
            }
        }
        return found;
    }

    private String fromIn(String question) {
        Matcher m = FROM.matcher(question);
        return m.find() ? normalize(m.group(1)) : null;
    }

    private String toIn(String question) {
        Matcher m = TO.matcher(question);
        return m.find() ? normalize(m.group(1)) : null;
    }

    /** 归一化为城市名（防「从北京到」抓到「北京到」里的多余字） */
    private String normalize(String raw) {
        for (String city : CITIES) {
            if (raw.contains(city)) {
                return city;
            }
        }
        return raw;
    }

    /** 从历史用户消息提取 from/to（终点优先：取最近一条含「去/到」消息中的城市） */
    private Map<String, String> cityFromHistory(List<Map<String, String>> history, String curFrom, String curTo) {
        Map<String, String> result = new HashMap<>();
        if (history == null) {
            return result;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, String> h = history.get(i);
            String role = h.getOrDefault("role", "user");
            String content = h.getOrDefault("content", "");
            if (!"user".equals(role)) {
                continue;
            }
            String to = toIn(content);
            String from = fromIn(content);
            if (result.get("to") == null && to != null && !to.equals(curFrom)) {
                result.put("to", to);
            }
            if (result.get("from") == null && from != null && !from.equals(curTo)) {
                result.put("from", from);
            }
            if (result.containsKey("from") && result.containsKey("to")) {
                break;
            }
        }
        return result;
    }
}
