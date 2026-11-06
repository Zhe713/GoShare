package com.goshare.notify.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goshare.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 订单事件消费者（阶段2「通知服务重定位」）
 *
 * <p>消费订单服务发出的业务事件（支付成功/取消/超时/退款），生成站内信与短信。
 * 消息格式为 JSON：{"type":"ORDER_PAID","userId":123,"orderNo":"...","amount":45.00}
 *
 * <p>消费防重：站内信生成是幂等的（同一订单同一事件重复消费会重复发信），
 * 这里用 bizType+orderNo 的语义幂等——实际由订单服务的状态机保证事件只发一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "GO_SHARE_ORDER_EVENT",
        consumerGroup = "go-share-notify-consumer",
        selectorExpression = "*")
public class OrderEventConsumer implements RocketMQListener<String> {

    private final NotifyService notifyService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String type = node.path("type").asText();
            long userId = node.path("userId").asLong();
            String orderNo = node.path("orderNo").asText();
            String amount = node.has("amount") ? node.path("amount").asText() : null;

            switch (type) {
                case "ORDER_PAID" -> notifyService.sendInbox(userId, "ORDER_PAID",
                        "订单支付成功", "您的订单 " + orderNo + " 已支付成功，请留意发车时间。", orderNo);
                case "ORDER_CANCELLED" -> notifyService.sendInbox(userId, "ORDER_CANCELLED",
                        "订单已取消", "您的订单 " + orderNo + " 已取消。", orderNo);
                case "ORDER_EXPIRED" -> notifyService.sendInbox(userId, "ORDER_EXPIRED",
                        "订单超时关闭", "您的订单 " + orderNo + " 因超时未支付已自动关闭。", orderNo);
                case "REFUND_SUCCESS" -> notifyService.sendInbox(userId, "REFUND_SUCCESS",
                        "退款到账", "您的订单 " + orderNo + " 退款 " + amount + " 元已原路退回。", orderNo);
                case "ORDER_RESCHEDULED" -> notifyService.sendInbox(userId, "ORDER_RESCHEDULED",
                        "改签成功", "您的订单 " + orderNo + " 已改签至新班次，改签免费，座位已换绑。", orderNo);
                default -> log.warn("[notify] 未知事件类型 type={}", type);
            }
        } catch (Exception e) {
            log.error("[notify] 订单事件消费失败 message={}: {}", message, e.getMessage());
        }
    }
}
