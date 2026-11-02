package com.goshare.trip.es;

import com.goshare.trip.entity.Trip;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 行程 ES 检索服务：文档同步 + 搜索（ES 不可用时由上层降级 MySQL）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TripEsService {

    private final ElasticsearchOperations operations;

    /** 同步单条行程到 ES（发布/修改时调用） */
    public void index(Trip trip) {
        try {
            TripDocument doc = new TripDocument();
            BeanUtils.copyProperties(trip, doc);
            operations.save(doc);
        } catch (Exception e) {
            log.warn("ES 同步行程失败 id={}，不影响主流程: {}", trip.getId(), e.getMessage());
        }
    }

    /** 删除行程文档（下架/取消时调用） */
    public void remove(Long tripId) {
        try {
            operations.delete(String.valueOf(tripId), TripDocument.class);
        } catch (Exception e) {
            log.warn("ES 删除行程失败 id={}: {}", tripId, e.getMessage());
        }
    }

    /** 搜索行程（ES 检索：起终点匹配 + 状态可订 + 只看未出发 + 可选日期范围） */
    public List<TripDocument> search(String fromCity, String toCity, LocalDate departDate) {
        Criteria criteria = new Criteria()
                .and(new Criteria("fromCity").is(fromCity))
                .and(new Criteria("toCity").is(toCity))
                .and(new Criteria("status").in(0, 1))
                .and(new Criteria("departureTime").greaterThanEqual(LocalDateTime.now()));
        if (departDate != null) {
            criteria.and(new Criteria("departureTime")
                    .greaterThanEqual(departDate.atStartOfDay())
                    .lessThan(departDate.plusDays(1).atStartOfDay()));
        }
        CriteriaQuery query = new CriteriaQuery(criteria);
        SearchHits<TripDocument> hits = operations.search(query, TripDocument.class);
        return hits.getSearchHits().stream()
                .map(hit -> hit.getContent())
                .collect(Collectors.toList());
    }
}
