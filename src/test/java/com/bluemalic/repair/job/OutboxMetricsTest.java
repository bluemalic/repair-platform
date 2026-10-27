package com.bluemalic.repair.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.IntegrationTest;
import com.bluemalic.repair.common.OutboxStatus;
import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.mapper.OutboxEventMapper;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class OutboxMetricsTest {

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void reportsPendingCountAndOldestAge() {
        TenantRoutingContext.run(TenantRoutingContext.CONTROL, () -> {
            long aggregateId = System.nanoTime();
            OutboxEvent event = new OutboxEvent();
            event.setTenantId(1L);
            event.setAggregateType("TICKET");
            event.setAggregateId(aggregateId);
            event.setEventType(TicketStatusChangedEvent.EVENT_TYPE);
            event.setEventId("test-metrics-" + UUID.randomUUID());
            event.setPayload("{}");
            event.setStatus(OutboxStatus.NEW.name());
            event.setAttempts(0);
            event.setNextAttemptAt(LocalDateTime.now());
            event.setCreatedAt(LocalDateTime.now().minusMinutes(10));
            outboxEventMapper.insert(event);
            try {
                outboxMetrics.refresh();

                assertThat(meterRegistry.get("repair.outbox.pending").gauge().value())
                        .as("至少包含刚插入的这条 NEW 事件").isGreaterThanOrEqualTo(1);
                assertThat(meterRegistry.get("repair.outbox.oldest_age_seconds").gauge().value())
                        .as("最老一条已经积压约 10 分钟").isGreaterThanOrEqualTo(540);
            } finally {
                outboxEventMapper.deleteById(event.getId());
            }
        });
    }
}
