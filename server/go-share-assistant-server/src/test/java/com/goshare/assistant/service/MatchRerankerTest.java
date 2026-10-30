package com.goshare.assistant.service;

import com.goshare.assistant.es.EsTripHit;
import com.goshare.assistant.nl.NlTripParser;
import com.goshare.assistant.nl.NlTripQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 智能撮合·细排打分器单测：权重排序 + 大件偏好修正 + 解释层
 */
class MatchRerankerTest {

    private EsTripHit hit(long id, long driverId, int hour, int seats, BigDecimal price) {
        EsTripHit h = new EsTripHit();
        h.setId(id);
        h.setDriverId(driverId);
        h.setFromCity("石家庄");
        h.setToCity("邯郸");
        h.setDepartureTime(LocalDateTime.of(2026, 10, 30, hour, 0));
        h.setRemainSeats(seats);
        h.setPrice(price);
        h.setStatus(0);
        return h;
    }

    @Test
    void 时间匹配优先排序() {
        // 用户要求 18 点：18:00 应排第一，20:30 第二
        NlTripQuery q = NlTripParser.parse("今天18点去邯郸");
        List<EsTripHit> candidates = List.of(
                hit(1, 1, 20, 3, new BigDecimal("35")),
                hit(2, 1, 18, 2, new BigDecimal("40"))
        );
        List<MatchReranker.ScoredTrip> ranked = MatchReranker.rerank(candidates,
                Map.of(1L, BigDecimal.valueOf(5.0)), Map.of(), q);
        assertEquals(2L, ranked.get(0).getTripId());
        assertTrue(ranked.get(0).getScore() > ranked.get(1).getScore());
    }

    @Test
    void 大件偏好_未备注大件降权() {
        NlTripQuery q = NlTripParser.parse("能带大件的行程");
        // 同时间同条件：一条备注大件、一条未备注
        EsTripHit withLuggage = hit(1, 1, 18, 2, new BigDecimal("40"));
        EsTripHit noRemark = hit(2, 1, 18, 2, new BigDecimal("40"));
        List<MatchReranker.ScoredTrip> ranked = MatchReranker.rerank(List.of(noRemark, withLuggage),
                Map.of(1L, BigDecimal.valueOf(5.0)),
                Map.of(1L, "可带大件，后备箱宽裕"), q);
        assertEquals(1L, ranked.get(0).getTripId());
        assertTrue(ranked.get(0).getReasons().contains("车主备注可带大件行李"));
    }

    @Test
    void 余座多者优先() {
        NlTripQuery q = NlTripParser.parse("去邯郸");
        // 无时间偏好时基准分一致，余座 3 > 1
        List<EsTripHit> candidates = List.of(
                hit(1, 1, 18, 1, new BigDecimal("40")),
                hit(2, 1, 18, 3, new BigDecimal("40"))
        );
        List<MatchReranker.ScoredTrip> ranked = MatchReranker.rerank(candidates,
                Map.of(1L, BigDecimal.valueOf(5.0)), Map.of(), q);
        assertEquals(2L, ranked.get(0).getTripId());
    }
}
