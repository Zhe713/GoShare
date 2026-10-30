package com.goshare.assistant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单消息表（B1 会话记录）
 */
@Data
@TableName("work_order_message")
public class WorkOrderMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单ID */
    private Long workOrderId;

    /** 发送方：USER乘客 AGENT客服 ASSISTANT助手 */
    private String sender;

    private String content;

    private LocalDateTime createdAt;
}
