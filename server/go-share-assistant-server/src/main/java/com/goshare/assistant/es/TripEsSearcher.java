package com.goshare.assistant.es;

import com.goshare.assistant.nl.NlTripQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 行程 ES 检索（A1 语义化搜索·粗排召回）
 * <p>
 * 动态 DSL：根据 NL 解析出的结构化条件按需组装查询——起终点/日期/时间段是可选的，
 * 未识别的条件不参与过滤（体现「动态查询 DSL 组装」考点，且语法边界可控防注入）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripEsSearcher {

    private final ElasticsearchOperations operations;

    /** 粗排召回：按条件动态组 DSL，返回可订行程（ES 不可用时上层降级 trip 服务搜索） */
    public List<EsTripHit> search(NlTripQuery query) {
        try {
            Criteria criteria = new Criteria()
                    .and(new Criteria("status").in(0, 1))
                    .and(new Criteria("departureTime").greaterThanEqual(LocalDateTime.now()));
            // 动态追加：起终点（NL 未识别则不追加，表示不限）
            if (query.getFromCity() != null) {
                criteria.and(new Criteria("fromCity").is(query.getFromCity()));
            }
            if (query.getToCity() != null) {
                criteria.and(new Criteria("toCity").is(query.getToCity()));
            }
            // 动态追加：日期范围
            if (query.getDepartDate() != null) {
                criteria.and(new Criteria("departureTime")
                        .greaterThanEqual(query.getDepartDate().atStartOfDay())
                        .lessThan(query.getDepartDate().plusDays(1).atStartOfDay()));
            }
            // 动态追加：时间段偏好（±2 小时窗，如 18点 → 16:00-20:00）
            if (query.getHourPrefer() != null) {
                LocalDateTime base = (query.getDepartDate() != null
                        ? query.getDepartDate().atStartOfDay()
                        : LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0));
                criteria.and(new Criteria("departureTime")
                        .greaterThanEqual(base.plusHours(Math.max(0, query.getHourPrefer() - 2)))
                        .lessThan(base.plusHours(Math.min(23, query.getHourPrefer() + 2) + 1)));
            }
            CriteriaQuery esQuery = new CriteriaQuery(criteria);
            SearchHits<EsTripHit> hits = operations.search(esQuery, EsTripHit.class);
            return hits.getSearchHits().stream()
                    .map(hit -> hit.getContent())
                    .limit(20)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[a1-es] ES 检索异常（由上层降级 trip 服务搜索）: {}", e.getMessage());
            return List.of();
        }
    }
}
