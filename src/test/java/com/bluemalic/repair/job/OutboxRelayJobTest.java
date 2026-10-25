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

import java.time.LocalDateTime;

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
        // 整个用例显式钉在平台库：不能依赖线程上残留的路由键。
        // CI 的测试顺序里，前一个用例可能留下租户键；那样 append 与 relay 会查两个库，
        // 表现就是“事件存在但 relay 永远处理不到”（本地顺序恰好为空，所以本地过、CI 红）。
        TenantRoutingContext.run(TenantRoutingContext.CONTROL, () -> {
            try {
                outboxService.append(1L, "TICKET", aggregateId, TicketStatusChangedEvent.EVENT_TYPE,
                        new TicketStatusChangedEvent(aggregateId, 10, 20, "DISPATCH", 9L));

                OutboxEvent before = outboxEventMapper.selectOne(
                        Wrappers.<OutboxEvent>lambdaQuery().eq(OutboxEvent::getAggregateId, aggregateId));
                assertThat(before).as("relay 之前事件必须已经和业务一起落库").isNotNull();
                assertThat(before.getStatus()).isEqualTo(OutboxStatus.NEW.name());
                assertThat(before.getNextAttemptAt())
                        .as("事件必须立即可投递：DATETIME 秒精度舍入会让它晚 1 秒，relay 就看不到")
                        .isBeforeOrEqualTo(LocalDateTime.now());

                // 测试里定时触发被 IntegrationTest 关掉了；这里直接驱动当前库的一轮 relay。
                relayJob.relayOnce();

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
        });
    }

    /**
     * claim 后进程崩溃会留下 SENDING；租约到期必须放回 NEW，否则那条事件永远不再被投递。
     * 还在租约内的行不能动——那可能是另一个实例正在处理。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reclaimsExpiredSendingLeaseButLeavesFreshOne() {
        LocalDateTime now = LocalDateTime.now();
        TenantRoutingContext.run(TenantRoutingContext.CONTROL, () -> {
            OutboxEvent expired = insertEvent(OutboxStatus.SENDING, now.minusSeconds(5));
            OutboxEvent fresh = insertEvent(OutboxStatus.SENDING, now.plusSeconds(30));
            try {
                relayJob.relayOnce();

                OutboxEvent reclaimed = outboxEventMapper.selectById(expired.getId());
                assertThat(reclaimed.getStatus()).as("租约到期的 SENDING 必须放回 NEW")
                        .isEqualTo(OutboxStatus.NEW.name());
                assertThat(reclaimed.getAttempts()).as("回收也算一次尝试，毒消息的退避才有上限")
                        .isEqualTo(1);
                assertThat(reclaimed.getNextAttemptAt()).as("回收后按退避推迟，不能立刻重投")
                        .isAfter(LocalDateTime.now());

                OutboxEvent untouched = outboxEventMapper.selectById(fresh.getId());
                assertThat(untouched.getStatus()).as("租约未到期的行不能动——可能另一个实例正在处理")
                        .isEqualTo(OutboxStatus.SENDING.name());
                assertThat(untouched.getAttempts()).isZero();
            } finally {
                outboxEventMapper.deleteById(expired.getId());
                outboxEventMapper.deleteById(fresh.getId());
            }
        });
    }

    private OutboxEvent insertEvent(OutboxStatus status, LocalDateTime nextAttemptAt) {
        long aggregateId = System.nanoTime();
        OutboxEvent event = new OutboxEvent();
        event.setTenantId(1L);
        event.setAggregateType("TICKET");
        event.setAggregateId(aggregateId);
        event.setEventType(TicketStatusChangedEvent.EVENT_TYPE);
        event.setEventId("test-lease-" + aggregateId);
        event.setPayload("{}");
        event.setStatus(status.name());
        event.setAttempts(0);
        event.setNextAttemptAt(nextAttemptAt);
        event.setCreatedAt(LocalDateTime.now().minusDays(1));
        outboxEventMapper.insert(event);
        return event;
    }
}
