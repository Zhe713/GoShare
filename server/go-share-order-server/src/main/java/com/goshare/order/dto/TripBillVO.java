package com.goshare.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 行程账单（F5：月度聚合，数据 100% 来自真实订单表）
 */
@Data
public class TripBillVO {

    /** 账单月份（yyyy-MM） */
    private String month;

    /** 拼车次数（有效订单数） */
    private Integer orderCount;

    /** 总里程（公里，旧订单无区间里程按 0 计） */
    private BigDecimal totalDistanceKm;

    /** 总花费（元，实付合计） */
    private BigDecimal totalPay;

    /** 网约车快车参考价（元，按 2.3 元/公里估算基准，确定性口径非编造） */
    private BigDecimal taxiBenchmark;

    /** 估算省钱（元 = 快车参考价 − 顺风车实付，可为负） */
    private BigDecimal savedEstimate;

    /** 最常线路 Top3 */
    private List<TopRoute> topRoutes;

    @Data
    public static class TopRoute {
        private String fromCity;
        private String toCity;
        private Integer count;
    }
}
