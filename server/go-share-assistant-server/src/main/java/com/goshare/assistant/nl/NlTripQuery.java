package com.goshare.assistant.nl;

import lombok.Data;

import java.time.LocalDate;

/**
 * 自然语言行程查询的结构化结果（A1 语义化搜索的第一步：NL→结构化）
 * 由规则模板 + 可选 LLM 抽取得到，字段均为可查询条件
 */
@Data
public class NlTripQuery {

    /** 出发城市（可空：未识别则不限） */
    private String fromCity;

    /** 到达城市（可空） */
    private String toCity;

    /** 出发日期（可空） */
    private LocalDate departDate;

    /** 时间段偏好（小时，可空）：如 18 表示用户想 18 点左右出发 */
    private Integer hourPrefer;

    /** 是否要求可带大件行李（可空） */
    private Boolean largeLuggage;

    /** 价格偏好：true=想便宜（排序权重用，不设硬性过滤） */
    private Boolean preferCheap;

    /** 是否识别出任何有效条件 */
    public boolean isEmpty() {
        return fromCity == null && toCity == null && departDate == null
                && hourPrefer == null && largeLuggage == null && preferCheap == null;
    }
}
