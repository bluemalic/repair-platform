package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.config.RocketMqProperties;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.service.OutboxEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * `notification.delivery=mq` 但没有 RocketMQ 时的本地投递器。
 *
 * <p>它让“通知走 Outbox”这件事在没有 broker 的本地/测试环境也能跑通：
 * Outbox relay 读到 {@code TICKET_NOTIFICATION} 后直接调用站内信服务。
 * RocketMQ 开启时 {@link #supports} 返回 false，由 MQ 消费者接管，避免重复发送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.notification", name = "delivery", havingValue = "mq")
class LocalNotificationEventHandler implements OutboxEventHandler {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final RocketMqProperties rocketMqProperties;

    @Override
    public boolean supports(String eventType) {
        return !rocketMqProperties.isEnabled() && NotificationCommand.EVENT_TYPE.equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        NotificationCommand command;
        try {
            command = objectMapper.readValue(event.getPayload(), NotificationCommand.class);
        } catch (Exception e) {
            // 解析失败重试也不会成功，记录后消费掉，避免 relay 无限重投
            log.warn("本地 Outbox 站内信事件解析失败 eventId={} payload={}",
                    event.getEventId(), event.getPayload(), e);
            return;
        }
        // 用 sendOrThrow：写库失败让 relay 重试，而不是静默丢通知
        notificationService.sendOrThrow(command.tenantId(), command.receiverId(), command.type(),
                command.title(), command.content(), command.ticketId());
        log.info("本地 Outbox 站内信已发送 eventId={} receiver={} type={}",
                event.getEventId(), command.receiverId(), command.type());
    }
}
