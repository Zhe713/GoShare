package com.goshare.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.assistant.dto.OrderDraftVO;
import com.goshare.assistant.nl.NlTripParser;
import com.goshare.assistant.nl.NlTripQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * F1 对话式下单（订单草稿确认卡）
 *
 * <p>设计原则（方案八 F1）：LLM 只负责「把话变成参数」，金额、余座、券能不能用
 * 全部由后端确定性计算，AI 永远不碰钱。本服务全程零模型调用：
 * NlTripParser 规则解析口语 → trip 服务查真实班次 → 确定性算价 → 产出确认卡。
 * 用户点「确认下单」后前端才调真实的 /order/create，券由下单接口结算，实付以下单为准。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDraftService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${goshare.assistant.trip-url:http://go-share-trip-server}")
    private String tripUrl;

    /** 取消政策文案（与 order FeeCalculator 口径一致，禁止编造与业务相反的内容） */
    private static final String CANCEL_POLICY =
            "发车前 2 小时外取消全额退款；2 小时内取消收实付 20% 违约金（封顶 50 元）。";

    /**
     * 口语 → 订单草稿确认卡。
     *
     * @param text 用户口语，如「帮我订明天下午6点去邯郸的，两个人」
     */
    public OrderDraftVO draft(String text) {
        OrderDraftVO vo = new OrderDraftVO();
        // 1. 确定性解析口语（城市/日期/时段/人数）
        NlTripQuery q = NlTripParser.parse(text);
        int seatCount = q.getSeatCount() == null ? 1 : q.getSeatCount();
        vo.setSeatCount(seatCount);

        if (q.getToCity() == null) {
            vo.setOrderable(false);
            vo.setMessage("想帮您订票，请告诉我目的地，例如「订明天去邯郸的，两个人」。");
            return vo;
        }
        if (q.getFromCity() == null) {
            vo.setOrderable(false);
            vo.setMessage("请告诉我出发城市，例如「从石家庄订明天去邯郸的，两个人」。");
            return vo;
        }

        // 2. 查真实班次（trip 服务，搜索口径与首页一致）
        List<JsonNode> trips = searchTrips(q.getFromCity(), q.getToCity(), q.getDepartDate());
        if (trips.isEmpty()) {
            vo.setOrderable(false);
            vo.setMessage(q.getFromCity() + "→" + q.getToCity()
                    + (q.getDepartDate() != null ? " " + q.getDepartDate() + " " : "")
                    + "暂无班次。您可以换个日期，或先在首页发布需求。");
            return vo;
        }

        // 3. 选班次：优先匹配时段偏好（±3 小时内最早出发的），无偏好取最早一班
        JsonNode picked = pickTrip(trips, q.getHourPrefer());
        if (picked == null) {
            vo.setOrderable(false);
            vo.setMessage(q.getFromCity() + "→" + q.getToCity() + " 在该时段暂无班次，可全天任意时段出发。");
            return vo;
        }

        // 4. 组装确认卡（金额确定性计算；余座不足时明确提示）
        long tripId = picked.path("id").asLong();
        int remainSeats = picked.path("remainSeats").asInt(0);
        // 单价口径与下单一致：有「每公里单价 + 全程里程」时按区间计价算（全程价），
        // 否则退回行程标价，避免"预估 40 实付 64"的口径不一致。
        BigDecimal unitPrice = picked.path("price").decimalValue();
        BigDecimal perKm = picked.path("pricePerKm").decimalValue();
        BigDecimal totalKm = picked.path("totalDistanceKm").decimalValue();
        if (perKm != null && perKm.signum() > 0 && totalKm != null && totalKm.signum() > 0) {
            unitPrice = perKm.multiply(totalKm).setScale(2, RoundingMode.HALF_UP);
        }
        vo.setTripId(tripId);
        vo.setFromCity(picked.path("fromCity").asText(q.getFromCity()));
        vo.setToCity(picked.path("toCity").asText(q.getToCity()));
        vo.setDepartureTime(parseTime(picked.path("departureTime").asText("")));
        vo.setRemainSeats(remainSeats);
        vo.setUnitPrice(unitPrice);
        vo.setEstimatedTotal(unitPrice.multiply(BigDecimal.valueOf(seatCount)).setScale(2, RoundingMode.HALF_UP));
        vo.setCancelPolicy(CANCEL_POLICY);
        vo.setStopCities(stopCities(picked));
        if (remainSeats < seatCount) {
            vo.setOrderable(false);
            vo.setMessage("该班次仅剩 " + remainSeats + " 座，不够 " + seatCount + " 人。可减少人数或换一班。");
            return vo;
        }
        vo.setOrderable(true);
        log.info("[order-draft] 确认卡已生成 tripId={} seatCount={} est={}", tripId, seatCount, vo.getEstimatedTotal());
        return vo;
    }

    /** 调 trip 服务搜索班次，返回原始 JSON 节点列表 */
    private List<JsonNode> searchTrips(String from, String to, java.time.LocalDate date) {
        try {
            StringBuilder url = new StringBuilder(tripUrl)
                    .append("/api/trip/search?fromCity=").append(from).append("&toCity=").append(to);
            if (date != null) {
                url.append("&departDate=").append(date);
            }
            var resp = restTemplate.exchange(url.toString(), HttpMethod.GET, null,
                    new ParameterizedTypeReference<com.goshare.common.result.Result<java.util.List<Object>>>() {
                    }).getBody();
            if (resp == null || resp.getData() == null) {
                return List.of();
            }
            List<JsonNode> result = new ArrayList<>();
            for (Object item : resp.getData()) {
                result.add(objectMapper.valueToTree(item));
            }
            return result;
        } catch (Exception e) {
            log.warn("[order-draft] 班次查询失败 from={} to={}: {}", from, to, e.getMessage());
            return List.of();
        }
    }

    /** 选班次：有时段偏好取 ±3 小时内最早的；无偏好取最早的可用班次 */
    private JsonNode pickTrip(List<JsonNode> trips, Integer hourPrefer) {
        JsonNode best = null;
        LocalDateTime bestTime = null;
        for (JsonNode t : trips) {
            if (t.path("status").asInt(0) != 0 && t.path("status").asInt(0) != 1) {
                continue; // 仅可预订状态
            }
            int remain = t.path("remainSeats").asInt(0);
            if (remain <= 0) {
                continue; // 满座不可订
            }
            LocalDateTime dep = parseTime(t.path("departureTime").asText(""));
            if (dep == null || dep.isBefore(LocalDateTime.now())) {
                continue; // 已发车
            }
            if (hourPrefer != null) {
                long diffHours = Math.abs(ChronoUnit.HOURS.between(dep,
                        dep.toLocalDate().atTime(hourPrefer, 0)));
                if (diffHours > 3) {
                    continue; // 不在偏好时段 ±3h 内
                }
            }
            if (bestTime == null || dep.isBefore(bestTime)) {
                best = t;
                bestTime = dep;
            }
        }
        return best;
    }

    private LocalDateTime parseTime(String s) {
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 站点城市序列（有途经点时展示可选上下车点） */
    private List<String> stopCities(JsonNode trip) {
        List<String> cities = new ArrayList<>();
        JsonNode stops = trip.path("stops");
        if (stops.isArray()) {
            stops.forEach(s -> cities.add(s.path("city").asText()));
        }
        return cities;
    }
}
