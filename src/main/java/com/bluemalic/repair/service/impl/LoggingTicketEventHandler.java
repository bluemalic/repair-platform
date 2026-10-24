package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.service.OutboxEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 阶段 1 的占位消费者：只记录事件，不产生副作用。
 *
 * <p>它的作用是让 relay 的“claim → 消费 → 标记 SENT → 失败重试”整条路先跑通；
 * 阶段 2 把通知/超时搬成独立消费者后，这个 Bean 可以被替换或移除。
 * **不要**在它里面加业务副作用——真正的消费者要能独立做幂等。
 */
@Slf4j
@Component
public class LoggingTicketEventHandler implements OutboxEventHandler {

    @Override
    public boolean supports(String eventType) {
        return TicketStatusChangedEvent.EVENT_TYPE.equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        log.info("Outbox 事件投递（阶段 1 占位） eventId={} tenantId={} type={} aggregate={}:{} payload={}",
                event.getEventId(), event.getTenantId(), event.getEventType(),
                event.getAggregateType(), event.getAggregateId(), event.getPayload());
    }
}
