package com.goshare.assistant.dto;

import lombok.Data;

import java.util.List;

/**
 * A2 取消挽留返回：费用解释（order 侧纯 Java 算）+ 替代方案 + 挽留话术（LLM 只说话术，不算钱）
 */
@Data
public class CancelRetentionVO {

    /** 是否已对该订单挽留过（幂等：同一订单只推一次） */
    private boolean alreadyRetained;

    /** 费用明细（来自 order 服务 FeeCalculator） */
    private FeePreview fee;

    /** 替代方案（同路线可订行程） */
    private List<Alternative> alternatives;

    /** 挽留话术（LLM 生成，金额数据由代码注入） */
    private String pitch;

    @Data
    public static class FeePreview {
        private String orderNo;
        private String status;
        private String totalPrice;
        private String couponDiscount;
        private String payPrice;
        private String liability;
        private String penalty;
        private String refundAmount;
        private String feeExplain;
        private String ruleExplain;
    }

    @Data
    public static class Alternative {
        private Long tripId;
        private String fromCity;
        private String toCity;
        private String departureTime;
        private String price;
        private Integer remainSeats;
    }
}
