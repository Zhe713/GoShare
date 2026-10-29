package com.goshare.order.mq;

import com.goshare.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 订单超时延时消息消费者
 *
 * <p>下单时发送的延时消息到期后投递到这里，负责关闭超时未支付的订单。
 * 消息重投（消费失败/重复消费）不会造成重复处理：</p>
 * <ol>
 *   <li>Redis 消费去重键：同一订单号第一次消费才处理（原子 setIfAbsent）；</li>
 *   <li>订单状态机兜底：已支付/已取消/已关闭的订单再次收到消息时静默跳过。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "GO_SHARE_ORDER",
        consumerGroup = "go-share-order-consumer",
        selectorExpression = "ORDER_TIMEOUT")
public class OrderTimeoutConsumer implements RocketMQListener<String> {

    /** 消费去重键前缀：order:timeout:consume:{订单号} */
    private static final String CONSUME_DEDUP_KEY_PREFIX = "order:timeout:consume:";

    private final OrderService orderService;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void onMessage(String orderNo) {
        // 消费防重复①：Redis 原子占位，同一订单号的重复消息只处理一次
        String dedupKey = CONSUME_DEDUP_KEY_PREFIX + orderNo;
        Boolean firstConsume = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofHours(1));
        if (Boolean.FALSE.equals(firstConsume)) {
            log.info("消费防重复：跳过已处理的消息 orderNo={}", orderNo);
            return;
        }
        // 消费防重复②：订单状态机兜底（非 CREATED 静默跳过），在 closeExpiredOrderByNo 内完成
        orderService.closeExpiredOrderByNo(orderNo);
    }
}
