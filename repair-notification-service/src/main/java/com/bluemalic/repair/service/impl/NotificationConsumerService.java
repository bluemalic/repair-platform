package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.channel.NotificationMessage;
import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.entity.ConsumedEvent;
import com.bluemalic.repair.mapper.ConsumedEventMapper;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MQ 通知消费的应用服务：**先按 eventId 幂等，再写站内信**，两步在同一个租户库事务里。
 *
 * <p>消费线程没有 HTTP 请求的租户上下文，所以必须按消息里的 tenantId 显式解析当前库；
 * 解析走 {@link TenantSchemaCatalog}（学校搬家后仍指向现在的库）。
 *
 * <p>失败语义：
 * <ul>
 *   <li>consumed_event 唯一键冲突 → 不重复写站内信，但继续尝试处理已有的外部投递记录；</li>
 *   <li>站内信与外部 delivery 记录同事务写入，任一写入失败就一起回滚并由 MQ 重投；</li>
 *   <li>外部发送在事务提交后执行，崩溃后由 PENDING delivery 重试任务恢复。</li>
 * </ul>
 *
 * <p><b>外部通道在事务提交之后</b>（阶段 4 · ADR-030）：站内信是事实来源，必须先进库；
 * 企业微信之类的外部投递失败只影响 {@code notification_delivery} 那一行，
 * 不会回滚已提交的站内信、也不会让 MQ 重投（重投只会把站内信又写一遍，撞幂等键白跑）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationConsumerService {

    private final ConsumedEventMapper consumedEventMapper;
    private final NotificationDeliveryServiceImpl deliveryService;
    private final TenantSchemaCatalog schemaCatalog;
    private final PlatformTransactionManager transactionManager;

    public void consume(long tenantId, String consumerGroup, String eventId, NotificationCommand command) {
        if (eventId == null || eventId.isBlank()) {
            // 老消息可能没有 eventId；用 MQ msgId 兜底由调用方保证，这里拒绝空值，
            // 避免"每次都当成新事件"导致重复通知。
            throw new IllegalArgumentException("通知事件缺少 eventId，无法幂等");
        }
        String routingKey = schemaCatalog.routingKeyFor(tenantId);
        NotificationMessage message = new NotificationMessage(tenantId, eventId,
                command.receiverId() == null ? 0L : command.receiverId(),
                command.type(), command.title(), command.content(), command.ticketId());

        TenantRoutingContext.run(routingKey, () -> {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> consumeInCurrentDatabase(tenantId, consumerGroup, eventId, message));
            // 同一事件重投时也检查 PENDING 记录：可能上次进程在事务提交后、外部发送前崩溃。
            deliveryService.deliverExternal(message);
        });
    }

    private void consumeInCurrentDatabase(long tenantId, String consumerGroup, String eventId,
                                          NotificationMessage message) {
        ConsumedEvent consumed = new ConsumedEvent();
        consumed.setTenantId(tenantId);
        consumed.setConsumerGroup(consumerGroup);
        consumed.setEventId(eventId);
        try {
            consumedEventMapper.insert(consumed);
        } catch (DuplicateKeyException e) {
            log.debug("通知事件已消费，跳过 eventId={} group={}", eventId, consumerGroup);
            return;
        }
        Long notificationId = deliveryService.deliverInApp(message);
        deliveryService.scheduleExternal(message, notificationId);
        log.info("通知事件已消费 eventId={} group={} receiver={} type={}",
                eventId, consumerGroup, message.receiverId(), message.type());
    }
}
