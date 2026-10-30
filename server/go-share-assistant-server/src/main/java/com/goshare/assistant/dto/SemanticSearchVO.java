package com.goshare.assistant.dto;

import lombok.Data;

import java.util.List;

/**
 * A1 语义化搜索返回：结构化回显 + 排序结果 + 解释（列表与金额均由代码产出，不靠 LLM）
 */
@Data
public class SemanticSearchVO {

    /** 用户原始输入 */
    private String rawText;

    /** NL 解析出的结构化条件回显 */
    private String parsedQuery;

    /** 排序结果 */
    private List<MatchItem> items;

    /** 解释层：为什么推荐这几单 */
    private String explanation;

    @Data
    public static class MatchItem {
        private Long tripId;
        private String fromCity;
        private String toCity;
        private String departureTime;
        private Integer remainSeats;
        private String price;
        private String trustScore;
        private String remark;
        private double score;
        private List<String> reasons;
    }
}
