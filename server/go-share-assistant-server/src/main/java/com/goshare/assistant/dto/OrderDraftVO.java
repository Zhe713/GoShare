package com.goshare.assistant.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单草稿确认卡（F1 对话式下单）
 *
 * <p>设计原则：AI 只产出"草稿"，不碰钱——班次来自真实行程数据、金额由后端确定性计算，
 * 用户点「确认下单」后前端才调真实的 /order/create（券在下单时由后端结算）。
 * 确认卡上的金额为预估（不含券），实付以下单接口返回为准。
 */
@Data
public class OrderDraftVO {

    /** 匹配到的行程ID（确认下单时传给 /order/create） */
    private Long tripId;

    private String fromCity;

    private String toCity;

    /** 出发时间 */
    private LocalDateTime departureTime;

    /** 行程余座 */
    private Integer remainSeats;

    /** 请求座位数（解析自口语，缺省 1） */
    private Integer seatCount;

    /** 单人价格（元） */
    private BigDecimal unitPrice;

    /** 预估总价（元，单价 × 人数，不含券） */
    private BigDecimal estimatedTotal;

    /** 取消政策摘要（与 FeeCalculator 口径一致） */
    private String cancelPolicy;

    /** 站点序列（有途经点时展示可选上下车点） */
    private List<String> stopCities;

    /** 确认卡能否直接下单：false = 没有匹配班次/解析不出路线，message 说明原因 */
    private Boolean orderable;

    /** 不可下单时的说明（如「没查到班次」「请告诉我目的地」） */
    private String message;
}
