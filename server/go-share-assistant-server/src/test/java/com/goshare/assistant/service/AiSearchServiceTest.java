package com.goshare.assistant.service;

import com.goshare.assistant.es.EsTripHit;
import com.goshare.assistant.dto.SemanticSearchVO;
import com.goshare.assistant.dto.TripSimpleVO;
import com.goshare.assistant.dto.UserTrustVO;
import com.goshare.assistant.es.TripEsSearcher;
import com.goshare.assistant.nl.NlTripQuery;
import com.goshare.common.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A1 语义搜索：ES 命中排序 / ES 空时降级 trip 服务 / 全空时给可执行提示。
 * 断言重点：金额与班次全部来自下游真实数据，解释文案里的车次也必须是真实命中的那一条。
 */
class AiSearchServiceTest {

    private TripEsSearcher esSearcher;
    private RestTemplate restTemplate;
    private AiSearchService service;

    @BeforeEach
    void setUp() {
        esSearcher = mock(TripEsSearcher.class);
        restTemplate = mock(RestTemplate.class);
        service = new AiSearchService(esSearcher, restTemplate);
        ReflectionTestUtils.setField(service, "tripUrl", "http://trip:8002");
        ReflectionTestUtils.setField(service, "userUrl", "http://user:8001");
    }

    private EsTripHit hit(long id, long driverId, int remain, String price, LocalDateTime dep) {
        EsTripHit h = new EsTripHit();
        h.setId(id);
        h.setDriverId(driverId);
        h.setFromCity("石家庄");
        h.setToCity("邯郸");
        h.setRemainSeats(remain);
        h.setPrice(new BigDecimal(price));
        h.setDepartureTime(dep);
        h.setStatus(0);
        return h;
    }

    @SuppressWarnings("unchecked")
    private void mockDownstream() {
        UserTrustVO trust = new UserTrustVO();
        trust.setTrustScore(new BigDecimal("4.9"));
        when(restTemplate.exchange(contains("/api/user/"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(trust)));
        TripSimpleVO trip = new TripSimpleVO();
        trip.setId(1L);
        trip.setRemark("可带大件");
        when(restTemplate.exchange(contains("/api/trip/"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(trip)));
    }

    @Test
    void semanticSearchReturnsRankedItemsFromEs() {
        LocalDateTime dep = LocalDateTime.now().plusDays(1).withHour(18).withMinute(0);
        when(esSearcher.search(any(NlTripQuery.class)))
                .thenReturn(List.of(hit(1L, 100L, 3, "45", dep), hit(2L, 100L, 1, "40", dep.plusHours(1))));
        mockDownstream();

        SemanticSearchVO vo = service.semanticSearch("明天18点从石家庄去邯郸，能带大件");

        assertEquals(2, vo.getItems().size());
        assertTrue(vo.getParsedQuery().contains("石家庄"));
        assertTrue(vo.getExplanation().contains("为你找到 2 个匹配行程"));
        // 解释里提到的首推车次必须来自真实命中数据
        assertTrue(vo.getExplanation().contains("石家庄→邯郸"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void semanticSearchFallsBackToTripServiceWhenEsEmpty() {
        when(esSearcher.search(any(NlTripQuery.class))).thenReturn(List.of());
        TripSimpleVO t = new TripSimpleVO();
        t.setId(9L);
        t.setDriverId(100L);
        t.setFromCity("石家庄");
        t.setToCity("邯郸");
        t.setRemainSeats(2);
        t.setPrice(new BigDecimal("45"));
        t.setDepartureTime(LocalDateTime.now().plusDays(1));
        t.setStatus(0);
        // 先铺通用下游，再单独覆盖 search 口径（Mockito 后设置的优先级更高）
        mockDownstream();
        when(restTemplate.exchange(contains("/api/trip/search"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(List.of(t))));

        SemanticSearchVO vo = service.semanticSearch("明天从石家庄去邯郸");

        assertEquals(1, vo.getItems().size());
        assertEquals(9L, vo.getItems().get(0).getTripId());
    }

    @Test
    void semanticSearchGivesActionableHintWhenNothingFound() {
        when(esSearcher.search(any(NlTripQuery.class))).thenReturn(List.of());

        SemanticSearchVO vo = service.semanticSearch("随便看看");

        assertTrue(vo.getItems().isEmpty());
        // 空结果不能只说"没找到"，要给出下一步动作（换时间 / 候补）
        assertTrue(vo.getExplanation().contains("候补"));
        assertEquals("未识别到明确条件，展示全部可订行程", vo.getParsedQuery());
    }

    @Test
    void semanticSearchWithCityButNoTrips() {
        when(esSearcher.search(any(NlTripQuery.class))).thenReturn(List.of());
        when(restTemplate.exchange(contains("/api/trip/search"), any(), any(),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Result.success(List.of())));

        SemanticSearchVO vo = service.semanticSearch("从石家庄去邯郸");

        assertTrue(vo.getItems().isEmpty());
        assertFalse(vo.getExplanation().isBlank());
    }

    @Test
    @SuppressWarnings("unchecked")
    void downstreamFailureDoesNotBreakSearch() {
        when(esSearcher.search(any(NlTripQuery.class)))
                .thenReturn(List.of(hit(3L, 7L, 2, "50", LocalDateTime.now().plusDays(1))));
        // 用户/行程服务异常：信任分退回默认 5.0，排序仍然出结果
        when(restTemplate.exchange(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
                .thenThrow(new RuntimeException("down"));

        SemanticSearchVO vo = service.semanticSearch("明天去邯郸");

        assertEquals(1, vo.getItems().size());
    }
}
