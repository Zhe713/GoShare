package com.goshare.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 天气意图规则提供器（真实数据·零模型调用）：
 * 识别「今天天气怎么样/石家庄下雨吗/冷不冷」等天气意图，
 * 提取城市后调用 notify 服务的天气接口返回真实天气（mock/http 由 notify 配置决定），
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

    /**
     * 命中天气意图返回真实天气的人话回答，否则返回 null
     */
    public String maybeAnswer(String question) {
        if (!INTENT.matcher(question).find()) {
            return null;
        }
        String city = extractCity(question);
        try {
            // 模板参数传 city：RestTemplate 负责正确 URL 编码（避免中文二次编码导致城市匹配失败）
            String url = notifyUrl + "/api/notify/weather?city={city}";
            String body = restTemplate.getForObject(url, String.class, city);
            JsonNode data = objectMapper.readTree(body).path("data");
            String weather = data.path("weather").asText("未知");
            String temperature = data.path("temperature").asText("--");
            String advice = data.path("advice").asText("");
            return String.format("【%s今日天气】%s，%s。%s", city, weather, temperature, advice);
        } catch (Exception e) {
            log.warn("[llm-gateway] 天气接口不可用，回落模型 city={} err={}", city, e.getMessage());
            return null;
        }
    }

    /** 提取城市：优先命中「去XX/XX的天气/XX明天」等，缺省取石家庄（示例运营主城） */
    private String extractCity(String question) {
        for (String city : CITIES) {
            if (question.contains(city)) {
                return city;
            }
        }
        return "石家庄";
    }
}
