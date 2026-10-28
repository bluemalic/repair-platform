package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.OutboxStatus;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.mapper.OutboxEventMapper;
import com.bluemalic.repair.service.OutboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void append(Long tenantId, String aggregateType, long aggregateId, String eventType, Object payload) {
        // outbox_event.next_attempt_at 是 DATETIME（秒精度）：带纳秒写入会被 MySQL 四舍五入
        // 到下一秒，relay 紧接着跑时会把“刚写入的事件”判成未到期。截到秒，保证立即可投递。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        OutboxEvent event = new OutboxEvent();
        event.setTenantId(tenantId == null ? 0L : tenantId);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setEventId(UUID.randomUUID().toString());
        event.setPayload(serialize(payload));
        event.setStatus(OutboxStatus.NEW.name());
        event.setAttempts(0);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        outboxEventMapper.insert(event);
        log.debug("Outbox 事件已登记 eventId={} tenantId={} type={} aggregate={}:{}",
                event.getEventId(), event.getTenantId(), eventType, aggregateType, aggregateId);
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            // 序列化失败是代码/数据问题，必须让业务事务回滚：宁可这次操作失败，
            // 也不要留下“状态已改、事件却丢了”的半截数据。
            throw new BizException(ErrorCode.SYSTEM_ERROR, "Outbox 事件序列化失败");
        }
    }
}
