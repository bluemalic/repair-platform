package com.bluemalic.repair.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 阶段 2 的占位消费者：只记录收到的事件，不产生业务副作用。
 *
 * <p>它的作用是先验证“Outbox → RocketMQ → 消费”整条通道；
 * 下一步把这段搬进独立的 `notification-service`，再让它调用站内信。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "repair.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqEventConsumer {

    public ConsumeConcurrentlyStatus consume(List<MessageExt> messages) {
        for (MessageExt message : messages) {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            log.info("RocketMQ 收到事件 topic={} tags={} keys={} eventId={} tenantId={} body={}",
                    message.getTopic(), message.getTags(), message.getKeys(),
                    message.getUserProperty("eventId"), message.getUserProperty("tenantId"), body);
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
