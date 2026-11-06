package com.goshare.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.goshare.notify.entity.NotifyMessage;
import com.goshare.notify.mapper.NotifyMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知服务（阶段2「通知服务重定位」）
 *
 * <p>从"天气 API 代理"升级为真正通知服务：
 * ① 站内信落库（notify_message 表）；② 短信渠道（mock）；③ 未读/已读管理。
 * 订单事件（支付/取消/超时/退款）由 RocketMQ 消费者触发，此处提供发送与查询能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyService {

    /** 短信渠道开关：演示环境 mock，仅打日志不真实发送 */
    private static final boolean SMS_MOCK = true;

    private final NotifyMessageMapper notifyMessageMapper;

    /** 发送站内信（落库） */
    public NotifyMessage sendInbox(Long userId, String bizType, String title, String content, String bizId) {
        NotifyMessage msg = new NotifyMessage();
        msg.setUserId(userId);
        msg.setBizType(bizType);
        msg.setTitle(title);
        msg.setContent(content);
        msg.setChannel("INBOX");
        msg.setReadFlag(0);
        msg.setBizId(bizId);
        notifyMessageMapper.insert(msg);
        log.info("[notify] 站内信已发送 userId={} bizType={} bizId={}", userId, bizType, bizId);
        return msg;
    }

    /** 发送短信（演示渠道 mock，仅打日志） */
    public void sendSms(Long userId, String phone, String title, String content) {
        if (SMS_MOCK) {
            log.info("[notify] 短信(mock) userId={} phone={} title={} content={}", userId, phone, title, content);
            return;
        }
        // 真实渠道：接入短信网关（演示项目不实现）
        log.warn("[notify] 短信渠道未配置，跳过真实发送 userId={}", userId);
    }

    /** 查询用户未读消息 */
    public List<NotifyMessage> listUnread(Long userId) {
        return notifyMessageMapper.selectList(new LambdaQueryWrapper<NotifyMessage>()
                .eq(NotifyMessage::getUserId, userId)
                .eq(NotifyMessage::getReadFlag, 0)
                .orderByDesc(NotifyMessage::getCreatedAt));
    }

    /** 查询用户全部消息 */
    public List<NotifyMessage> listAll(Long userId) {
        return notifyMessageMapper.selectList(new LambdaQueryWrapper<NotifyMessage>()
                .eq(NotifyMessage::getUserId, userId)
                .orderByDesc(NotifyMessage::getCreatedAt));
    }

    /** 标记已读 */
    public void markRead(Long userId, Long messageId) {
        NotifyMessage msg = notifyMessageMapper.selectById(messageId);
        if (msg == null || !msg.getUserId().equals(userId)) {
            return;
        }
        msg.setReadFlag(1);
        notifyMessageMapper.updateById(msg);
    }

    /** 全部标记已读 */
    public void markAllRead(Long userId) {
        notifyMessageMapper.selectList(new LambdaQueryWrapper<NotifyMessage>()
                        .eq(NotifyMessage::getUserId, userId)
                        .eq(NotifyMessage::getReadFlag, 0))
                .forEach(msg -> {
                    msg.setReadFlag(1);
                    notifyMessageMapper.updateById(msg);
                });
    }

    /** 未读数 */
    public long unreadCount(Long userId) {
        return notifyMessageMapper.selectCount(new LambdaQueryWrapper<NotifyMessage>()
                .eq(NotifyMessage::getUserId, userId)
                .eq(NotifyMessage::getReadFlag, 0));
    }
}
