package com.goshare.assistant.dto;

import lombok.Data;

import java.util.List;

/**
 * B1 工单分析返回：会话摘要 + 回复草稿
 */
@Data
public class WorkOrderAnalyzeVO {

    private Long workOrderId;

    private String orderNo;

    /** 问题分类（规则兜底打标） */
    private String category;

    /** 工单状态 */
    private String status;

    /** AI 会话摘要 */
    private String summary;

    /** AI 回复草稿 */
    private String replyDraft;

    /** 会话消息条数 */
    private Integer messageCount;

    /** 是否首次生成（摘要只触发一次） */
    private Boolean firstTime;
}
