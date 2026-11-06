package com.goshare.assistant.llm.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 价格参谋工具（F3：这单值不值）
 *
 * <p>调订单服务的线路价格统计接口，返回同线路近 30 天的均价/最低价/中位数/单量。
 * 这是纯统计、确定性计算，零幻觉风险——模型只负责把数字讲成人话，
 * 金额全部来自真实订单表，面试时可打开数据库对照。
 */
@Slf4j
@Component
public class PriceAdvisorTool implements AssistantTool {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${goshare.ai.order-service-url}")
    private String orderServiceUrl;

    public PriceAdvisorTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "price_advice";
    }

    @Override
    public String description() {
        return "查询某条城际线路近 30 天的历史成交均价、最低价、中位数与成交量，用于判断「这个价格贵不贵」。当用户询问某线路价格是否划算、贵不贵、值不值、行情如何时调用。参数：fromCity 出发城市，toCity 到达城市。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fromCity", Map.of("type", "string", "description", "出发城市，如：北京"));
        props.put("toCity", Map.of("type", "string", "description", "到达城市，如：上海"));
        return Map.of("type", "object", "properties", props,
                "required", new String[]{"fromCity", "toCity"});
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String from = args.path("fromCity").asText("");
            String to = args.path("toCity").asText("");
            if (from.isBlank() || to.isBlank()) {
                return "出发地和目的地不能为空，请补充完整后再查询。";
            }
            String url = orderServiceUrl + "/api/order/price-stats?fromCity=" + from + "&toCity=" + to + "&days=30";
            Result<Map<String, Object>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<Result<Map<String, Object>>>() {
                    }).getBody();
            Map<String, Object> data = resp == null ? null : resp.getData();
            if (data == null) {
                return "价格统计服务暂时不可用，请稍后再试。";
            }
            Object count = data.get("orderCount");
            if (count == null || Integer.parseInt(String.valueOf(count)) == 0) {
                return from + "→" + to + " 近 30 天暂无成交记录，暂无历史价格可参考。";
            }
            return String.format(
                    "%s→%s 近 30 天共 %s 单：均价 %s 元，最低 %s 元，中位数 %s 元。",
                    from, to,
                    count,
                    data.get("avgPrice"), data.get("minPrice"), data.get("medianPrice"));
        } catch (Exception e) {
            log.warn("[price_advice] 价格统计调用失败: {}", e.getMessage());
            return "价格行情查询失败：" + e.getMessage();
        }
    }
}
