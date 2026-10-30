package com.goshare.assistant.service;

import com.goshare.assistant.dto.SemanticSearchVO;
import com.goshare.assistant.es.EsTripHit;
import com.goshare.assistant.es.TripEsSearcher;
import com.goshare.assistant.nl.NlTripParser;
import com.goshare.assistant.nl.NlTripQuery;
import com.goshare.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A1 语义化行程搜索与智能撮合（核心服务）
 * <p>
 * 流程：NL→结构化（规则模板）→ ES 动态 DSL 粗排 → 特征并行获取（CompletableFuture）→ 细排打分 → 解释层。
 * 列表与金额一律由代码产出，LLM 不参与生成（方案约定，防编造数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSearchService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM月dd日 HH:mm");

    private final TripEsSearcher esSearcher;
    private final RestTemplate restTemplate;

    @Value("${goshare.ai.trip-service-url}")
    private String tripUrl;

    @Value("${goshare.ai.user-service-url}")
    private String userUrl;

    /**
     * 语义化搜索：rawText（如"周五18点从石家庄去邯郸，能带大件"）→ 排序结果 + 解释
     */
    public SemanticSearchVO semanticSearch(String rawText) {
        NlTripQuery query = NlTripParser.parse(rawText);

        // 粗排：ES 动态 DSL（异常/无结果时降级 trip 服务搜索）
        List<EsTripHit> hits = esSearcher.search(query);
        if (hits.isEmpty()) {
            hits = fallbackSearch(query);
        }
        if (hits.isEmpty()) {
            SemanticSearchVO empty = new SemanticSearchVO();
            empty.setRawText(rawText);
            empty.setParsedQuery(describe(query));
            empty.setItems(List.of());
            empty.setExplanation("没有找到匹配行程，换个时间或放宽条件试试（也可去「候补」排队等座位）。");
            return empty;
        }

        // 细排特征并行获取：车主信任分（user 服务）+ 余座校准/备注（trip 服务）
        Map<Long, BigDecimal> trustScores = fetchTrustScores(hits);
        Map<Long, String> remarks = fetchRemarks(hits);
        List<MatchReranker.ScoredTrip> ranked = MatchReranker.rerank(hits, trustScores, remarks, query);

        // 组装返回
        SemanticSearchVO vo = new SemanticSearchVO();
        vo.setRawText(rawText);
        vo.setParsedQuery(describe(query));
        vo.setExplanation(buildExplanation(rawText, ranked));
        vo.setItems(ranked.stream().map(this::toItem).collect(Collectors.toList()));
        return vo;
    }

    /** ES 不可用/无结果时的降级：调 trip 服务标准搜索接口 */
    private List<EsTripHit> fallbackSearch(NlTripQuery query) {
        if (query.getFromCity() == null || query.getToCity() == null) {
            return List.of();
        }
        try {
            String url = tripUrl + "/api/trip/search?fromCity=" + query.getFromCity()
                    + "&toCity=" + query.getToCity()
                    + (query.getDepartDate() != null ? "&departDate=" + query.getDepartDate() : "");
            Result<List<com.goshare.assistant.dto.TripSimpleVO>> resp = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<Result<List<com.goshare.assistant.dto.TripSimpleVO>>>() {
                    }).getBody();
            if (resp == null || resp.getData() == null) {
                return List.of();
            }
            return resp.getData().stream().map(t -> {
                EsTripHit h = new EsTripHit();
                h.setId(t.getId());
                h.setDriverId(t.getDriverId());
                h.setFromCity(t.getFromCity());
                h.setToCity(t.getToCity());
                h.setDepartureTime(t.getDepartureTime());
                h.setRemainSeats(t.getRemainSeats());
                h.setPrice(t.getPrice());
                h.setStatus(t.getStatus());
                return h;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[a1] trip 降级搜索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 并行获取车主信任分（去重 driverId） */
    private Map<Long, BigDecimal> fetchTrustScores(List<EsTripHit> hits) {
        Set<Long> driverIds = hits.stream().map(EsTripHit::getDriverId).collect(Collectors.toSet());
        List<CompletableFuture<Map.Entry<Long, BigDecimal>>> futures = driverIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> {
                    try {
                        Result<com.goshare.assistant.dto.UserTrustVO> resp = restTemplate.exchange(
                                userUrl + "/api/user/" + id, HttpMethod.GET, null,
                                new ParameterizedTypeReference<Result<com.goshare.assistant.dto.UserTrustVO>>() {
                                }).getBody();
                        BigDecimal trust = resp != null && resp.getData() != null
                                ? resp.getData().getTrustScore()
                                : BigDecimal.valueOf(5.0);
                        return Map.entry(id, trust);
                    } catch (Exception e) {
                        return Map.entry(id, BigDecimal.valueOf(5.0));
                    }
                }))
                .collect(Collectors.toList());
        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** 并行获取行程备注（大件等偏好特征） */
    private Map<Long, String> fetchRemarks(List<EsTripHit> hits) {
        Set<Long> tripIds = hits.stream().map(EsTripHit::getId).collect(Collectors.toSet());
        List<CompletableFuture<Map.Entry<Long, String>>> futures = tripIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> {
                    try {
                        Result<com.goshare.assistant.dto.TripSimpleVO> resp = restTemplate.exchange(
                                tripUrl + "/api/trip/" + id, HttpMethod.GET, null,
                                new ParameterizedTypeReference<Result<com.goshare.assistant.dto.TripSimpleVO>>() {
                                }).getBody();
                        return Map.entry(id, resp != null && resp.getData() != null
                                ? resp.getData().getRemark() : "");
                    } catch (Exception e) {
                        return Map.entry(id, "");
                    }
                }))
                .collect(Collectors.toList());
        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private String describe(NlTripQuery q) {
        List<String> parts = new ArrayList<>();
        if (q.getFromCity() != null) {
            parts.add("出发 " + q.getFromCity());
        }
        if (q.getToCity() != null) {
            parts.add("到达 " + q.getToCity());
        }
        if (q.getDepartDate() != null) {
            parts.add(q.getDepartDate().getMonthValue() + "月" + q.getDepartDate().getDayOfMonth() + "日");
        }
        if (q.getHourPrefer() != null) {
            parts.add(q.getHourPrefer() + " 点左右");
        }
        if (Boolean.TRUE.equals(q.getLargeLuggage())) {
            parts.add("可带大件");
        }
        if (Boolean.TRUE.equals(q.getPreferCheap())) {
            parts.add("优先便宜");
        }
        return parts.isEmpty() ? "未识别到明确条件，展示全部可订行程" : String.join("、", parts);
    }

    private String buildExplanation(String rawText, List<MatchReranker.ScoredTrip> ranked) {
        if (ranked.isEmpty()) {
            return "没有匹配行程";
        }
        MatchReranker.ScoredTrip top = ranked.get(0);
        return "为你找到 " + ranked.size() + " 个匹配行程，按「时间匹配 / 余座 / 车主信任分 / 价格」综合排序。"
                + "首推「" + top.getFromCity() + "→" + top.getToCity() + " " + top.getDepartureTime().format(FMT)
                + "」：" + String.join("；", top.getReasons()) + "。";
    }

    private SemanticSearchVO.MatchItem toItem(MatchReranker.ScoredTrip st) {
        SemanticSearchVO.MatchItem item = new SemanticSearchVO.MatchItem();
        item.setTripId(st.getTripId());
        item.setFromCity(st.getFromCity());
        item.setToCity(st.getToCity());
        item.setDepartureTime(st.getDepartureTime().format(FMT));
        item.setRemainSeats(st.getRemainSeats());
        item.setPrice(st.getPrice().toPlainString());
        item.setTrustScore(st.getTrustScore().toPlainString());
        item.setRemark(st.getRemark());
        item.setScore(st.getScore());
        item.setReasons(st.getReasons());
        return item;
    }
}
