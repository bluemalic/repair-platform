package com.bluemalic.repair.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.IntegrationTest;
import com.bluemalic.repair.common.OutboxStatus;
import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.mapper.OutboxEventMapper;
import com.bluemalic.repair.service.OutboxService;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class OutboxRelayJobTest {

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private OutboxRelayJob relayJob;

    /**
     * 不走测试事务：relay 的 claim/标记必须在**已提交**的数据上验证，
     * 否则会受测试事务可见性影响（这个用例第一版就因此偶发失败过一次）。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void relayOnceClaimsAndMarksEventSent() {
        long aggregateId = System.nanoTime();
        try {
            outboxService.append(1L, "TICKET", aggregateId, TicketStatusChangedEvent.EVENT_TYPE,
                    new TicketStatusChangedEvent(aggregateId, 10, 20, "DISPATCH", 9L));

            OutboxEvent before = outboxEventMapper.selectOne(
                    Wrappers.<OutboxEvent>lambdaQuery().eq(OutboxEvent::getAggregateId, aggregateId));
            assertThat(before).as("relay 之前事件必须已经和业务一起落库").isNotNull();
            assertThat(before.getStatus()).isEqualTo(OutboxStatus.NEW.name());

            // 测试里定时触发被 IntegrationTest 关掉了；这里直接驱动当前库的一轮 relay。
            TenantRoutingContext.run(TenantRoutingContext.CONTROL, relayJob::relayOnce);

            OutboxEvent event = outboxEventMapper.selectOne(
                    Wrappers.<OutboxEvent>lambdaQuery().eq(OutboxEvent::getAggregateId, aggregateId));
            assertThat(event).isNotNull();
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT.name());
            assertThat(event.getSentAt()).isNotNull();
        } finally {
            // NOT_SUPPORTED 会真的写入测试库，用例自己清理
            outboxEventMapper.delete(Wrappers.<OutboxEvent>lambdaQuery()
                    .eq(OutboxEvent::getAggregateId, aggregateId));
        }
    }
}
