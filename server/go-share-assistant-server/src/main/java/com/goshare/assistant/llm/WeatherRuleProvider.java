package com.goshare.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 天气意图规则提供器（真实数据·零模型调用）：
 * 识别「今天天气怎么样/邯郸下雨吗/11.4号的天气/明天冷不冷」等天气意图，
 * 城市优先从问句提取，缺失时从多轮上下文（历史用户消息）回捞，杜绝答错城市；
 * 日期支持「明天/后天/X月X日/X.X/号」解析，天气接口按目标日期返回真实预报；
 * 未命中天气意图或接口异常返回 null，回落到规则/模型关卡。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherRuleProvider {

    /** 示例运营及周边城市（识别天气问句中的城市） */
    private static final List<String> CITIES = List.of(
            "石家庄", "北京", "保定", "邯郸", "天津", "唐山", "廊坊", "张家口", "承德",
            "秦皇岛", "沧州", "邢台", "衡水", "广州", "上海", "深圳", "杭州", "成都",
            "重庆", "西安", "武汉", "南京", "郑州", "济南", "青岛", "太原", "沈阳",
            "长沙", "南昌", "福州", "厦门", "昆明", "合肥", "哈尔滨", "呼和浩特", "兰州"
    );

    /** 天气意图关键词 */
    private static final Pattern INTENT = Pattern.compile("天气|下雨|下雪|温度|气温|冷不冷|热不热|穿什么|带伞|台风|雾霾|沙尘");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${goshare.assistant.notify-url:http://go-share-notify-server}")
    private String notifyUrl;

    /** 天气查询意图（解析结果，纯本地计算，不触发外部调用） */
    public static class WeatherQuery {
        public final String city;
        public final LocalDate date;

        public WeatherQuery(String city, LocalDate date) {
            this.city = city;
            this.date = date;
        }
    }

    /**
     * 解析天气意图：命中返回城市+日期（城市可空），未命中返回 null
     */
    public WeatherQuery resolve(String question, List<Map<String, String>> history) {
        if (!INTENT.matcher(question).find()) {
            return null;
        }
        return new WeatherQuery(extractCity(question, history), DateParser.parse(question));
    }

    /**
     * 拉取真实天气并组装人话回答；城市缺失时诚实反问（不默认乱猜城市）。
     * 接口异常返回「暂不可用」兜底，绝不返回模拟数据。
     */
    public String fetch(WeatherQuery wq) {
        String city = wq.city;
        if (city == null) {
            return "您想问哪个城市的天气呢？比如石家庄、邯郸、保定、北京~";
        }
        try {
            // 模板参数传 city/date：RestTemplate 负责正确 URL 编码（避免中文二次编码导致城市匹配失败）
            Map<String, String> params = new HashMap<>();
            params.put("city", city);
            String url = notifyUrl + "/api/notify/weather?city={city}";
            if (wq.date != null) {
                params.put("date", wq.date.toString());
                url += "&date={date}";
            }
            String body = restTemplate.getForObject(url, String.class, params);
            JsonNode data = objectMapper.readTree(body).path("data");
            String weather = data.path("weather").asText("未知");
            String temperature = data.path("temperature").asText("--");
            String advice = data.path("advice").asText("");
            String label = DateParser.label(wq.date);
            return String.format("【%s%s天气】%s，%s。%s", city, label, weather, temperature, advice);
        } catch (Exception e) {
            log.warn("[llm-gateway] 天气接口不可用，回落模型 city={} err={}", city, e.getMessage());
            return null;
        }
    }

    /** 提取城市：优先问句命中（按出现位置取第一个，避免「邯郸今天天气和北京…」误取北京）；其次从历史用户消息（倒序）回捞；都没有返回 null（由调用方诚实反问） */
    private String extractCity(String question, List<Map<String, String>> history) {
        String best = null;
        int bestIdx = Integer.MAX_VALUE;
        for (String city : CITIES) {
            int idx = question.indexOf(city);
            if (idx >= 0 && idx < bestIdx) {
                bestIdx = idx;
                best = city;
            }
        }
        if (best != null) {
            return best;
        }
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                Map<String, String> h = history.get(i);
                String role = h.getOrDefault("role", "user");
                if (!"user".equals(role)) {
                    continue;
                }
                String content = h.getOrDefault("content", "");
                for (String city : CITIES) {
                    if (content.contains(city)) {
                        return city;
                    }
                }
            }
        }
        return null;
    }
}
