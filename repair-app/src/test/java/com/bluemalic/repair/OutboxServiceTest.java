package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.OutboxStatus;
import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.mapper.OutboxEventMapper;
import com.bluemalic.repair.service.OutboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class OutboxServiceTest {

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void appendWritesOneNewEventWithSerializedPayload() throws Exception {
        long aggregateId = System.nanoTime();

        outboxService.append(1L, "TICKET", aggregateId, TicketStatusChangedEvent.EVENT_TYPE,
                new TicketStatusChangedEvent(aggregateId, 10, 20, "DISPATCH", 9L));

        List<OutboxEvent> events = outboxEventMapper.selectList(
                Wrappers.<OutboxEvent>lambdaQuery().eq(OutboxEvent::getAggregateId, aggregateId));
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getTenantId()).isEqualTo(1L);
        assertThat(event.getEventType()).isEqualTo(TicketStatusChangedEvent.EVENT_TYPE);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW.name());
        assertThat(event.getAttempts()).isZero();
        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertThat(payload.path("action").asText()).isEqualTo("DISPATCH");
        assertThat(payload.path("ticketId").asLong()).isEqualTo(aggregateId);
        assertThat(payload.path("fromStatus").asInt()).isEqualTo(10);
        assertThat(payload.path("toStatus").asInt()).isEqualTo(20);
    }
}
