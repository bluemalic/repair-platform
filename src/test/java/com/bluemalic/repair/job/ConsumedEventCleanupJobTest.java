package com.bluemalic.repair.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.IntegrationTest;
import com.bluemalic.repair.entity.ConsumedEvent;
import com.bluemalic.repair.mapper.ConsumedEventMapper;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class ConsumedEventCleanupJobTest {

    @Autowired
    private ConsumedEventCleanupJob cleanupJob;

    @Autowired
    private ConsumedEventMapper consumedEventMapper;

    /**
     * 不走测试事务：DELETE 的结果必须在**已提交**的行上验证，
     * 否则测的是同一事务里的可见性，而不是清理是否真的发生。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deletesOnlyRowsOlderThanRetention() {
        String group = "test-retention-" + System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        // 显式钉在平台库：不能依赖线程上残留的路由键（与 OutboxRelayJobTest 同一个坑）
        TenantRoutingContext.run(TenantRoutingContext.CONTROL, () -> {
            try {
                long expiredId = insert(group, "expired", now.minusDays(31));
                long freshId = insert(group, "fresh", now.minusDays(1));

                int deleted = cleanupJob.cleanExpired(now.minusDays(30));

                assertThat(deleted).as("平台库这次至少删掉了一条过期记录").isGreaterThanOrEqualTo(1);
                assertThat(exists(expiredId)).as("超过保留期的幂等记录必须删掉").isFalse();
                assertThat(exists(freshId)).as("保留期内的记录不能删——删了消息重投会重复写通知").isTrue();
            } finally {
                // NOT_SUPPORTED 会真的写入测试库，用例自己清理
                consumedEventMapper.delete(Wrappers.<ConsumedEvent>lambdaQuery()
                        .eq(ConsumedEvent::getConsumerGroup, group));
            }
        });
    }

    /**
     * 保留期配成 0 / 负数时必须一行都不删。这个任务的误操作后果是"删光幂等记录"，
     * 而 MQ 还在重投窗口内的话，删掉就等于允许重复消费——所以宁可什么都不做。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void refusesToDeleteWhenRetentionIsNotPositive() {
        String group = "test-retention-guard-" + System.nanoTime();
        LocalDateTime now = LocalDateTime.now();
        TenantRoutingContext.run(TenantRoutingContext.CONTROL, () -> {
            long id = insert(group, "ancient", now.minusDays(365));
            try {
                // 共享上下文里的单例：改完必须在 finally 还原，否则会影响后续用例
                ReflectionTestUtils.setField(cleanupJob, "enabled", true);
                ReflectionTestUtils.setField(cleanupJob, "retentionDays", 0);

                cleanupJob.clean();

                assertThat(exists(id)).as("保留期非法时不能删任何记录").isTrue();
            } finally {
                ReflectionTestUtils.setField(cleanupJob, "enabled", false);
                ReflectionTestUtils.setField(cleanupJob, "retentionDays", 30);
                consumedEventMapper.delete(Wrappers.<ConsumedEvent>lambdaQuery()
                        .eq(ConsumedEvent::getConsumerGroup, group));
            }
        });
    }

    private long insert(String consumerGroup, String eventId, LocalDateTime consumedAt) {
        ConsumedEvent event = new ConsumedEvent();
        event.setTenantId(1L);
        event.setConsumerGroup(consumerGroup);
        event.setEventId(eventId);
        event.setConsumedAt(consumedAt);
        consumedEventMapper.insert(event);
        return event.getId();
    }

    private boolean exists(long id) {
        return consumedEventMapper.selectById(id) != null;
    }
}
