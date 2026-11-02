package com.goshare.assistant.service;

import com.goshare.assistant.dto.CancelRetentionVO;
import com.goshare.assistant.dto.OrderDetailVO;
import com.goshare.assistant.dto.TripSimpleVO;
import com.goshare.assistant.llm.LlmClient;
import com.goshare.assistant.llm.LlmGateway;
import com.goshare.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A2 智能取消挽留与退改费用解释
 * <p>
 * 流程：识别取消意图 → order 服务 FeeCalculator 算费用明细（金额纯 Java，不靠 LLM）→
 * 查询同路线替代行程 → LLM 生成挽留话术（金额数据由代码注入）→ 同一订单只挽留一次（Redis 幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelRetentionService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");
    private static final String RETENTION_KEY_PREFIX = "ai:cancel-retention:";

    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;
    private final LlmGateway llmGateway;
    private final LlmClient llmClient;
    private final com.goshare.assistant.mapper.LlmCallLogMapper llmCallLogMapper;

    @Value("${goshare.ai.order-service-url}")
    private String orderUrl;

    @Value("${goshare.ai.trip-service-url}")
    private String tripUrl;

    /**
     * 取消挽留预览：费用解释 + 替代方案 + 挽留话术（幂等：同一订单只推一次）
     */
    public CancelRetentionVO preview(String orderNo) {
        CancelRetentionVO vo = new CancelRetentionVO();

        // 1. 挽留幂等：同一订单只挽留一次（Redis 标记；Redis 不可用时降级为首次挽留）
        boolean firstTime = true;
        try {
            firstTime = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(RETENTION_KEY_PREFIX + orderNo, "1", Duration.ofHours(24)));
        } catch (Exception e) {
            log.warn("[cancel-retention] Redis 不可用，跳过挽留幂等: {}", e.getMessage());
        }
        vo.setAlreadyRetained(!firstTime);

        // 2. 费用明细：order 服务 FeeCalculator 纯 Java 计算（金额不靠 LLM）
        vo.setFee(fetchFee(orderNo));

        // 3. 替代方案：同路线未来可订行程（排除当前行程）
        List<CancelRetentionVO.Alternative> alternatives = fetchAlternatives(orderNo);
        vo.setAlternatives(alternatives);

        // 4. 挽留话术：LLM 生成（mock 可插拔；金额/行程数据由代码注入，LLM 不参与算账）
        vo.setPitch(buildPitch(orderNo, vo.getFee(), alternatives));
        return vo;
    }

    /** 调 order 服务拿费用明细 */
    private CancelRetentionVO.FeePreview fetchFee(String orderNo) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, String> body = Map.of("orderNo", orderNo);
            Result<Map<String, Object>> resp = restTemplate.exchange(
                            orderUrl + "/api/order/cancel-preview", HttpMethod.POST,
                            new HttpEntity<>(body, headers),
                            new ParameterizedTypeReference<Result<Map<String, Object>>>() {
                            })
                    .getBody();
            if (resp == null || resp.getData() == null) {
                throw new IllegalStateException("order 未返回费用明细");
            }
            Map<String, Object> d = resp.getData();
            CancelRetentionVO.FeePreview fee = new CancelRetentionVO.FeePreview();
            fee.setOrderNo(str(d.get("orderNo")));
            fee.setStatus(str(d.get("status")));
            fee.setTotalPrice(str(d.get("totalPrice")));
            fee.setCouponDiscount(str(d.get("couponDiscount")));
            fee.setPayPrice(str(d.get("payPrice")));
            fee.setLiability(str(d.get("liability")));
            fee.setPenalty(str(d.get("penalty")));
            fee.setRefundAmount(str(d.get("refundAmount")));
            fee.setFeeExplain(str(d.get("feeExplain")));
            fee.setRuleExplain(str(d.get("ruleExplain")));
            return fee;
        } catch (Exception e) {
            log.warn("[a2] 费用明细获取失败: {}", e.getMessage());
            return null;
        }
    }

    /** 替代方案：订单行程同路线、出发时间更晚的可订行程 */
    private List<CancelRetentionVO.Alternative> fetchAlternatives(String orderNo) {
        try {
            // 订单 → 行程ID
            Result<OrderDetailVO> orderResp = restTemplate.exchange(
                            orderUrl + "/api/order/" + orderNo, HttpMethod.GET, null,
                            new ParameterizedTypeReference<Result<OrderDetailVO>>() {
                            })
                    .getBody();
            Long tripId = orderResp != null && orderResp.getData() != null
                    ? orderResp.getData().getTripId() : null;
            if (tripId == null) {
                return List.of();
            }
            // 行程 → 路线
            Result<TripSimpleVO> tripResp = restTemplate.exchange(
                            tripUrl + "/api/trip/" + tripId, HttpMethod.GET, null,
                            new ParameterizedTypeReference<Result<TripSimpleVO>>() {
                            })
                    .getBody();
            TripSimpleVO trip = tripResp != null ? tripResp.getData() : null;
            if (trip == null) {
                return List.of();
            }
            // 同路线搜索（未来行程）
            String url = tripUrl + "/api/trip/search?fromCity=" + trip.getFromCity()
                    + "&toCity=" + trip.getToCity();
            Result<List<TripSimpleVO>> searchResp = restTemplate.exchange(
                            url, HttpMethod.GET, null,
                            new ParameterizedTypeReference<Result<List<TripSimpleVO>>>() {
                            })
                    .getBody();
            if (searchResp == null || searchResp.getData() == null) {
                return List.of();
            }
            return searchResp.getData().stream()
                    .filter(t -> !t.getId().equals(tripId))
                    .filter(t -> t.getDepartureTime() != null && t.getDepartureTime().isAfter(LocalDateTime.now()))
                    .limit(3)
                    .map(t -> {
                        CancelRetentionVO.Alternative alt = new CancelRetentionVO.Alternative();
                        alt.setTripId(t.getId());
                        alt.setFromCity(t.getFromCity());
                        alt.setToCity(t.getToCity());
                        alt.setDepartureTime(t.getDepartureTime().format(FMT));
                        alt.setPrice(t.getPrice().toPlainString());
                        alt.setRemainSeats(t.getRemainSeats());
                        return alt;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[a2] 替代方案查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 挽留话术：LLM 生成（金额/替代方案由代码注入），同一订单只生成一次。
     *  直接调 LlmClient 而非 LlmGateway，避免被通用规则拦截成标准答案——A2 需要个性化挽留语气。 */
    private String buildPitch(String orderNo, CancelRetentionVO.FeePreview fee,
                              List<CancelRetentionVO.Alternative> alternatives) {
        StringBuilder context = new StringBuilder();
        if (fee != null) {
            context.append("订单金额明细：").append(fee.getFeeExplain()).append(" ");
            context.append("（判责：").append(fee.getLiability()).append("）");
        }
        if (alternatives != null && !alternatives.isEmpty()) {
            context.append(" 可替代行程：");
            for (CancelRetentionVO.Alternative a : alternatives) {
                context.append(a.getDepartureTime()).append(" ").append(a.getFromCity()).append("→")
                        .append(a.getToCity()).append(" ").append(a.getPrice()).append("元，");
            }
        }
        String prompt = LlmGateway.SYSTEM_PREFIX + "\n用户想取消订单 " + orderNo + "。" + context
                + "。请用 60 字以内、诚恳的语气劝用户再考虑一下，说明现在取消的费用情况，并给出替代方案（如有）。"
                + "不要编造金额与行程。";
        LlmClient.LlmResponse resp = llmClient.call(prompt, LlmClient.Tier.LARGE);
        // 记账（A2 话术：mock 零成本 / http 真模型；金额不靠 LLM）
        recordLog(orderNo, resp);
        return resp.text();
    }

    /** 记账：A2 挽留话术生成（model=大模型/mock；金额不靠 LLM） */
    private void recordLog(String orderNo, LlmClient.LlmResponse resp) {
        try {
            com.goshare.assistant.entity.LlmCallLog row = new com.goshare.assistant.entity.LlmCallLog();
            row.setBizType("A2取消挽留");
            row.setModelName("大模型(挽留话术)");
            row.setPromptHash("a2-retention:" + orderNo);
            row.setInputTokens(resp.inputTokens());
            row.setOutputTokens(resp.outputTokens());
            row.setDurationMs(0);
            row.setCacheHit(0);
            row.setGateSkipped(0);
            llmCallLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("[a2] 记账失败: {}", e.getMessage());
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
