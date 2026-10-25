package com.bluemalic.repair.mq;

import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.config.RocketMqProperties;
import com.bluemalic.repair.service.impl.NotificationConsumerService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 阶段 2 的 MQ 消费者：
 * <ul>
 *   <li>{@code TICKET_STATUS_CHANGED}：暂时只记日志，下一步抽 notification-service 的事件入口；</li>
 *   <li>{@code TICKET_NOTIFICATION}：交给 {@link NotificationConsumerService}
 *       （按 eventId 幂等 + 按 tenantId 路由到租户库 + 写站内信）。</li>
 * </ul>
 *
 * <p>解析失败直接消费掉（重试也不会成功）；写库/发送失败返回 {@code RECONSUME_LATER}，
 * 由 RocketMQ 重投——幂等由 consumed_event 唯一键保证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqEventConsumer {

    private final NotificationConsumerService notificationConsumerService;
    private final ObjectMapper objectMapper;
    private final RocketMqProperties properties;

    public ConsumeConcurrentlyStatus consume(List<MessageExt> messages) {
        for (MessageExt message : messages) {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (NotificationCommand.EVENT_TYPE.equals(message.getTags())) {
                if (!consumeNotification(message, body)) {
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            } else {
                log.info("RocketMQ 收到事件 topic={} tags={} keys={} eventId={} tenantId={} body={}",
                        message.getTopic(), message.getTags(), message.getKeys(),
                        message.getUserProperty("eventId"), message.getUserProperty("tenantId"), body);
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    /** @return true = 可以消费掉；false = 需要 MQ 重投 */
    private boolean consumeNotification(MessageExt message, String body) {
        NotificationCommand command;
        try {
            command = objectMapper.readValue(body, NotificationCommand.class);
        } catch (JsonProcessingException e) {
            // 坏消息重试也不会成功，记日志后消费掉
            log.warn("RocketMQ 站内信事件解析失败 keys={} body={}", message.getKeys(), body, e);
            return true;
        }

        Long tenantId = command.tenantId();
        if (tenantId == null) {
            tenantId = parseTenantId(message.getUserProperty("tenantId"));
        }
        if (tenantId == null) {
            log.warn("RocketMQ 站内信事件缺少 tenantId keys={} body={}", message.getKeys(), body);
            return true;
        }

        String eventId = message.getUserProperty("eventId");
        if (eventId == null || eventId.isBlank()) {
            eventId = message.getMsgId();
        }
        if (eventId == null || eventId.isBlank()) {
            log.warn("RocketMQ 站内信事件缺少 eventId/msgId keys={} body={}", message.getKeys(), body);
            return true;
        }

        try {
            notificationConsumerService.consume(tenantId, properties.getConsumerGroup(), eventId, command);
            return true;
        } catch (Exception e) {
            log.warn("RocketMQ 站内信消费失败，将重投 eventId={} tenantId={}",
                    eventId, tenantId, e);
            return false;
        }
    }

    private Long parseTenantId(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
