package com.bluemalic.repair.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.IntegrationTest;
import com.bluemalic.repair.common.OutboxStatus;
import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.entity.ConsumedEvent;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.mapper.ConsumedEventMapper;
import com.bluemalic.repair.mapper.OutboxEventMapper;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class EventRetentionJobTest {

    @Autowired
    private EventRetentionJob retentionJob;

    @Autowired
    private ConsumedEventMapper consumedEventMapper;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    /**
     * 不走测试事务：DELETE 的结果必须在**已提交**的行上验证，
     * 否则测的是同一事务里的可见性，而不是清理是否真的发生。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletesOnlyConsumedRowsOlderThanRetention() {
        String group = "test-retention-" + System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        // 显式钉在平台库：不能依赖线程上残留的路由键（与 OutboxRelayJobTest 同一个坑）
        TenantRoutingContext.run(TenantRoutingContext.CONTROL, () -> {
            try {
                long expiredId = insertConsumed(group, "expired", now.minusDays(31));
                long freshId = insertConsumed(group, "fresh", now.minusDays(1));

                EventRetentionJob.Deleted deleted = retentionJob.cleanExpired(now.minusDays(30));

                assertThat(deleted.consumed()).as("平台库这次至少删掉了一条过期记录").isGreaterThanOrEqualTo(1);
                assertThat(existsConsumed(expiredId)).as("超过保留期的幂等记录必须删掉").isFalse();
                assertThat(existsConsumed(freshId)).as("保留期内的记录不能删——删了消息重投会重复写通知").isTrue();
            } finally {
                // NOT_SUPPORTED 会真的写入测试库，用例自己清理
                consumedEventMapper.delete(Wrappers.<ConsumedEvent>lambdaQuery()
                        .eq(ConsumedEvent::getConsumerGroup, group));
            }
        });
    }

    /**
     * outbox 只删 SENT：NEW 还没投递，SENDING 可能是崩溃后待回收的行，删掉就是丢事件。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletesOnlySentOutboxRowsOlderThanRetention() {
        LocalDateTime now = LocalDateTime.now();
        TenantRoutingContext.run(TenantRoutingContext.CONTROL, () -> {
            long expiredSent = insertOutbox(OutboxStatus.SENT, now.minusDays(31), now.minusDays(31));
            long freshSent = insertOutbox(OutboxStatus.SENT, now.minusDays(1), now.minusDays(31));
            long expiredNew = insertOutbox(OutboxStatus.NEW, null, now.minusDays(31));
            try {
                EventRetentionJob.Deleted deleted = retentionJob.cleanExpired(now.minusDays(30));

                assertThat(deleted.outbox()).as("平台库这次至少删掉了一条过期的 SENT 事件").isGreaterThanOrEqualTo(1);
                assertThat(existsOutbox(expiredSent)).as("超过保留期且已投递的事件才可删").isFalse();
                assertThat(existsOutbox(freshSent)).as("保留期内的已投递事件不能删").isTrue();
                assertThat(existsOutbox(expiredNew)).as("还没投递的事件永远不能删——删了就是丢事件").isTrue();
            } finally {
                for (long id : new long[]{expiredSent, freshSent, expiredNew}) {
                    outboxEventMapper.deleteById(id);
                }
            }
        });
    }

    /**
     * 保留期配成 0 / 负数时必须一行都不删。这个任务的误操作后果是“删光幂等记录/未投递事件”，
     * 而 MQ 还在重投窗口内的话，删掉就等于允许重复消费——所以宁可什么都不做。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void refusesToDeleteWhenRetentionIsNotPositive() {
        String group = "test-retention-guard-" + System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        TenantRoutingContext.run(TenantRoutingContext.CONTROL, () -> {
            long id = insertConsumed(group, "ancient", now.minusDays(365));
            try {
                // 共享上下文里的单例：改完必须在 finally 还原，否则会影响后续用例
                ReflectionTestUtils.setField(retentionJob, "enabled", true);
                ReflectionTestUtils.setField(retentionJob, "retentionDays", 0);

                retentionJob.clean();

                assertThat(existsConsumed(id)).as("保留期非法时不能删任何记录").isTrue();
            } finally {
                ReflectionTestUtils.setField(retentionJob, "enabled", false);
                ReflectionTestUtils.setField(retentionJob, "retentionDays", 30);
                consumedEventMapper.delete(Wrappers.<ConsumedEvent>lambdaQuery()
                        .eq(ConsumedEvent::getConsumerGroup, group));
            }
        });
    }

    private long insertConsumed(String consumerGroup, String eventId, LocalDateTime consumedAt) {
        ConsumedEvent event = new ConsumedEvent();
        event.setTenantId(1L);
        event.setConsumerGroup(consumerGroup);
        event.setEventId(eventId);
        event.setConsumedAt(consumedAt);
        consumedEventMapper.insert(event);
        return event.getId();
    }

    private boolean existsConsumed(long id) {
        return consumedEventMapper.selectById(id) != null;
    }

    private long insertOutbox(OutboxStatus status, LocalDateTime sentAt, LocalDateTime createdAt) {
        OutboxEvent event = new OutboxEvent();
        event.setTenantId(1L);
        event.setAggregateType("TICKET");
        event.setAggregateId(System.nanoTime());
        event.setEventType(TicketStatusChangedEvent.EVENT_TYPE);
        event.setEventId("test-retention-" + UUID.randomUUID());
        event.setPayload("{}");
        event.setStatus(status.name());
        event.setAttempts(0);
        event.setNextAttemptAt(createdAt);
        event.setCreatedAt(createdAt);
        event.setSentAt(sentAt);
        outboxEventMapper.insert(event);
        return event.getId();
    }

    private boolean existsOutbox(long id) {
        return outboxEventMapper.selectById(id) != null;
    }
}
