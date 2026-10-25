package com.bluemalic.repair.mq;

import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.service.NotificationService;
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
 *   <li>{@code TICKET_NOTIFICATION}：解析成 {@link NotificationCommand}，调用站内信服务。</li>
 * </ul>
 *
 * <p>解析失败直接消费掉（重试也不会成功），只留 WARN；站内信发送的瞬时失败由
 * {@code NotificationService} 自己吞掉，这是它“通知不阻塞主流程”的既有契约。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqEventConsumer {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public ConsumeConcurrentlyStatus consume(List<MessageExt> messages) {
        for (MessageExt message : messages) {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            if (NotificationCommand.EVENT_TYPE.equals(message.getTags())) {
                consumeNotification(message, body);
            } else {
                log.info("RocketMQ 收到事件 topic={} tags={} keys={} eventId={} tenantId={} body={}",
                        message.getTopic(), message.getTags(), message.getKeys(),
                        message.getUserProperty("eventId"), message.getUserProperty("tenantId"), body);
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private void consumeNotification(MessageExt message, String body) {
        try {
            NotificationCommand command = objectMapper.readValue(body, NotificationCommand.class);
            notificationService.send(command.tenantId(), command.receiverId(), command.type(),
                    command.title(), command.content(), command.ticketId());
            log.info("RocketMQ 站内信已发送 eventId={} receiver={} type={}",
                    message.getUserProperty("eventId"), command.receiverId(), command.type());
        } catch (Exception e) {
            log.warn("RocketMQ 站内信事件解析失败 keys={} body={}", message.getKeys(), body, e);
        }
    }
}
