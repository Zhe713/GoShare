package com.goshare.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知实体（对应表 notify_message）
 *
 * <p>阶段2「通知服务重定位」：从"天气 API 代理"升级为真正通知服务的落点。
 * 订单支付/取消/超时/退款等事件产生站内信，用户可查询未读消息。
 */
@Data
@TableName("notify_message")
public class NotifyMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收用户ID */
    private Long userId;

    /** 业务类型 ORDER_PAID订单支付 ORDER_CANCELLED订单取消 ORDER_EXPIRED订单超时 REFUND_SUCCESS退款成功 TRIP_FULL行程满座 */
    private String bizType;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 渠道 INBOX站内信 SMS短信 */
    private String channel;

    /** 是否已读 0否 1是 */
    private Integer readFlag;

    /** 关联业务ID（订单号/退款号等） */
    private String bizId;

    private LocalDateTime createdAt;
}
