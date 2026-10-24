package com.bluemalic.repair.mq;

import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.config.RocketMqProperties;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.service.OutboxEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 把 Outbox 事件发到 RocketMQ（阶段 2 · ADR-023）。
 *
 * <p>它只是 {@link OutboxEventHandler} 的一个实现：Outbox relay 负责 claim/重试，
 * 这里只负责“把 payload 发出去”。发送失败抛异常，relay 会把事件标回 NEW 并按退避重试。
 *
 * <p>消息 key 用工单 ID（aggregateId），保证同一工单可顺序消费；eventId 放用户属性，
 * 供消费者做幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqOutboxPublisher implements OutboxEventHandler {

    private final DefaultMQProducer producer;
    private final RocketMqProperties properties;

    @Override
    public boolean supports(String eventType) {
        return TicketStatusChangedEvent.EVENT_TYPE.equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        String body = event.getPayload() == null ? "{}" : event.getPayload();
        Message message = new Message(properties.getTopic(), event.getEventType(),
                body.getBytes(StandardCharsets.UTF_8));
        message.setKeys(String.valueOf(event.getAggregateId()));
        message.putUserProperty("eventId", event.getEventId());
        message.putUserProperty("tenantId", String.valueOf(event.getTenantId()));
        try {
            SendResult result = producer.send(message);
            log.info("Outbox 事件已发送 RocketMQ eventId={} key={} topic={} msgId={}",
                    event.getEventId(), event.getAggregateId(), properties.getTopic(), result.getMsgId());
        } catch (Exception e) {
            // 让 relay 捕获并标记重试；不要把异常吞掉，否则事件会被误标 SENT
            throw new IllegalStateException("RocketMQ 发送失败 eventId=" + event.getEventId(), e);
        }
    }
}
