package com.goshare.assistant.service;

import com.goshare.assistant.es.EsTripHit;
import com.goshare.assistant.nl.NlTripQuery;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A1 语义化搜索·细排特征打分器（MatchReranker）
 * <p>
 * 特征（对齐嘀嗒匹配规则：时间匹配/顺路/信任/余座）+ 解释层：
 * 每个候选给出总分与命中原因（"为什么推荐这几单"可追溯）。
 */
@Slf4j
public final class MatchReranker {

    /** 特征权重：时间匹配 0.35 / 余座 0.25 / 信任分 0.25 / 价格 0.15 */
    private static final double W_TIME = 0.35;
    private static final double W_SEAT = 0.25;
    private static final double W_TRUST = 0.25;
    private static final double W_PRICE = 0.15;

    private MatchReranker() {
    }

    /**
     * 细排打分：粗排候选 + 实时特征（余座校准、车主信任分）→ 得分降序 + 命中原因
     *
     * @param candidates  粗排召回
     * @param trustScores driverId → 信任分（调 user 服务取得）
     * @param remarks     tripId → 备注（调 trip 服务取得，如"可带大件"）
     * @param query       用户结构化条件（解释层引用）
     */
    public static List<ScoredTrip> rerank(List<EsTripHit> candidates,
                                          java.util.Map<Long, BigDecimal> trustScores,
                                          java.util.Map<Long, String> remarks,
                                          NlTripQuery query) {
        List<ScoredTrip> scored = new ArrayList<>();
        for (EsTripHit hit : candidates) {
            double timeScore = timeScore(hit, query);
            double seatScore = seatScore(hit);
            double trustScore = trust(hit, trustScores);
            double priceScore = priceScore(hit, query);
            double total = W_TIME * timeScore + W_SEAT * seatScore + W_TRUST * trustScore + W_PRICE * priceScore;

            // 大件偏好修正：用户要带大件时，车主明确可带大件的优先，未备注的降权
            String remark = remarks.getOrDefault(hit.getId(), "");
            if (Boolean.TRUE.equals(query.getLargeLuggage()) && !remark.contains("大件")) {
                total -= 0.15;
            }

            ScoredTrip st = new ScoredTrip();
            st.setTripId(hit.getId());
            st.setDriverId(hit.getDriverId());
            st.setFromCity(hit.getFromCity());
            st.setToCity(hit.getToCity());
            st.setDepartureTime(hit.getDepartureTime());
            st.setRemainSeats(hit.getRemainSeats());
            st.setPrice(hit.getPrice());
            st.setTrustScore(trustScores.getOrDefault(hit.getDriverId(), BigDecimal.valueOf(5.0)));
            st.setRemark(remark);
            st.setScore(round(total));
            st.setReasons(buildReasons(st, query, timeScore, seatScore, priceScore));
            scored.add(st);
        }
        scored.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return scored;
    }

    /** 时间匹配：无偏好给基准分；有偏好 ±1h 满分、±3h 0.8、更远 0.4 */
    private static double timeScore(EsTripHit hit, NlTripQuery query) {
        if (query.getHourPrefer() == null) {
            return 0.5;
        }
        int h = hit.getDepartureTime().getHour();
        int diff = Math.abs(h - query.getHourPrefer());
        if (diff <= 1) {
            return 1.0;
        }
        if (diff <= 3) {
            return 0.8;
        }
        return 0.4;
    }

    /** 余座：≥2 满分、1 座 0.6、0 座 0（无座排最后） */
    private static double seatScore(EsTripHit hit) {
        Integer remain = hit.getRemainSeats();
        if (remain == null || remain <= 0) {
            return 0.0;
        }
        if (remain >= 2) {
            return 1.0;
        }
        return 0.6;
    }

    /** 信任分：0-5 归一化 */
    private static double trust(EsTripHit hit, java.util.Map<Long, BigDecimal> trustScores) {
        BigDecimal t = trustScores.getOrDefault(hit.getDriverId(), BigDecimal.valueOf(5.0));
        return t.doubleValue() / 5.0;
    }

    /** 价格：用户偏好便宜时价格越低分越高；无偏好给基准 0.6 */
    private static double priceScore(EsTripHit hit, NlTripQuery query) {
        if (!Boolean.TRUE.equals(query.getPreferCheap())) {
            return 0.6;
        }
        BigDecimal price = hit.getPrice() == null ? BigDecimal.ZERO : hit.getPrice();
        // 演示数据价格区间 0-100 元，归一化
        return Math.max(0.0, 1.0 - price.doubleValue() / 100.0);
    }

    /** 解释层：把命中的特征转成人话 */
    private static List<String> buildReasons(ScoredTrip st, NlTripQuery query,
                                             double timeScore, double seatScore, double priceScore) {
        List<String> reasons = new ArrayList<>();
        if (timeScore >= 0.8) {
            reasons.add("出发时间" + (query.getHourPrefer() != null
                    ? "正好匹配你要求的 " + query.getHourPrefer() + " 点前后"
                    : "符合你的出行日期"));
        }
        if (seatScore >= 0.6) {
            reasons.add("还有 " + st.getRemainSeats() + " 个空座可订");
        }
        if (st.getTrustScore().doubleValue() >= 4.5) {
            reasons.add("车主信任分 " + st.getTrustScore().toPlainString() + " 分，靠谱车主");
        }
        if (priceScore >= 0.9) {
            reasons.add("价格 " + st.getPrice().toPlainString() + " 元，符合你要便宜的需求");
        }
        if (st.getRemark() != null && st.getRemark().contains("大件")) {
            reasons.add("车主备注可带大件行李");
        }
        if (reasons.isEmpty()) {
            reasons.add("路线完全匹配你的需求");
        }
        return reasons;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** 打分结果（含解释） */
    @Data
    public static class ScoredTrip {
        private Long tripId;
        private Long driverId;
        private String fromCity;
        private String toCity;
        private java.time.LocalDateTime departureTime;
        private Integer remainSeats;
        private BigDecimal price;
        private BigDecimal trustScore;
        private String remark;
        private double score;
        private List<String> reasons;
    }
}
