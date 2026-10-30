package com.goshare.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单表（B1 客服助手：会话摘要 + 回复草稿）
 */
@Data
@TableName("work_order")
public class WorkOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联订单号 */
    private String orderNo;

    /** 乘客用户ID */
    private Long passengerId;

    /** 问题分类（规则兜底打标：退款/改期/候补/其他） */
    private String category;

    /** 工单状态 OPEN待处理 PROCESSING处理中 RESOLVED已解决 */
    private String status;

    /** AI 会话摘要 */
    private String summary;

    /** AI 回复草稿 */
    private String replyDraft;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
