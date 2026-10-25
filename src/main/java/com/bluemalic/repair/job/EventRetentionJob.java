package com.bluemalic.repair.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.OutboxStatus;
import com.bluemalic.repair.entity.ConsumedEvent;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.mapper.ConsumedEventMapper;
import com.bluemalic.repair.mapper.OutboxEventMapper;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * 事件类追加表的保留期清理（阶段 2 · ADR-023）：{@code consumed_event} 与 {@code outbox_event}。
 *
 * <p><b>为什么必须清理</b>：两张都是追加型表，每条消息都留一行。不清理就是无限增长，
 * 而它们上面的唯一索引（{@code uk_consumer_event} / {@code uk_event_id}）越大，
 * 每次插入越慢——最终拖住的是**消息消费与业务提交本身**。这种退化是渐进的，不会有任何报错。
 *
 * <p><b>为什么 outbox 只删 SENT</b>：{@code NEW} 还没投递；{@code SENDING} 可能是
 * “claim 后进程崩溃”留下的待回收行——删掉就是丢事件。只有 {@code SENT} 的载荷已经交付，
 * 表里留着只为人工排查，超过保留期才可以删。
 *
 * <p><b>保留期取多长</b>：只要覆盖 MQ 的最大重投窗口即可。RocketMQ 默认只保留 72 小时消息，
 * 超过这个时间的重复投递不会发生；默认 30 天有充足余量，也给人工排查留了窗口。
 * 两张表共用同一个保留期，因为它们回答的是同一个问题（“这条消息还可能需要重放吗”）。
 *
 * <p><b>逐库 + 分批</b>：和 {@code OutboxRelayJob} 同一套遍历方式（平台库 + 每个已搬家租户库），
 * 一次 DELETE 扫不到所有库。每张表每个库每批 {@value #BATCH_SIZE} 行、最多
 * {@value #MAX_BATCHES_PER_DATABASE} 批，避免一条大 DELETE 长时间持锁挡住写入；
 * 单轮删不完的留到下一次，不会堆积（每天产生的量远小于单轮上限）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventRetentionJob {

    private static final int BATCH_SIZE = 1000;
    private static final int MAX_BATCHES_PER_DATABASE = 100;

    private final ConsumedEventMapper consumedEventMapper;
    private final OutboxEventMapper outboxEventMapper;
    private final TenantSchemaCatalog schemaCatalog;

    /** 测试里关掉定时触发（{@code @IntegrationTest} 已设 false）；手动调仍是可用的。 */
    @Value("${repair.event-retention.enabled:true}")
    private boolean enabled;

    @Value("${repair.event-retention.days:30}")
    private int retentionDays;

    /** 凌晨 4:20：与演示数据重置（4:00）错开，避免同一分钟里两个任务抢库。可配。 */
    @Scheduled(cron = "${repair.event-retention.cron:0 20 4 * * *}")
    public void clean() {
        if (!enabled) {
            return;
        }
        if (retentionDays < 1) {
            // 配错成 0 或负数时宁可一行都不删：这个任务操作失误的后果是“删光幂等记录/未投递事件”，不可逆。
            log.warn("事件表保留期配置非法（days={}），本轮不清理", retentionDays);
            return;
        }
        try {
            Deleted deleted = cleanExpired(LocalDateTime.now().minusDays(retentionDays));
            if (deleted.total() > 0) {
                log.info("事件表保留期清理完成 consumed_event={} outbox_event(SENT)={}（保留 {} 天）",
                        deleted.consumed(), deleted.outbox(), retentionDays);
            }
        } catch (Exception e) {
            // 不向外抛：抛出去只会被调度器再打一遍；下次到点自然重试。
            // 一天只跑一次，失败必须留下完整堆栈，否则查不动。
            log.error("事件表保留期清理失败（下个周期会重试）", e);
        }
    }

    /**
     * 删除所有库里早于 cutoff 的过期行。包级可见是为了让测试直接驱动，
     * 不用等凌晨的 cron，也不受 {@code enabled} 开关影响。
     */
    Deleted cleanExpired(LocalDateTime cutoff) {
        Deleted deleted = new Deleted(0, 0);
        for (String routingKey : scanTargets()) {
            try {
                Deleted inThisDatabase = TenantRoutingContext.call(routingKey,
                        () -> deleteExpiredInCurrentDatabase(cutoff));
                deleted = deleted.plus(inThisDatabase);
            } catch (Exception e) {
                // 一个库失败不影响其它库；下一轮继续
                log.warn("事件表清理失败 routingKey={} err={}", routingKey, e.getMessage());
            }
        }
        return deleted;
    }

    private Deleted deleteExpiredInCurrentDatabase(LocalDateTime cutoff) {
        int consumed = deleteInBatches(() -> consumedEventMapper.delete(
                Wrappers.<ConsumedEvent>lambdaQuery()
                        .lt(ConsumedEvent::getConsumedAt, cutoff)
                        .last("LIMIT " + BATCH_SIZE)));

        // sent_at 为空的行一定不是 SENT；显式判空是为了防御“状态改错、sent_at 没写”的脏数据
        int outbox = deleteInBatches(() -> outboxEventMapper.delete(
                Wrappers.<OutboxEvent>lambdaQuery()
                        .eq(OutboxEvent::getStatus, OutboxStatus.SENT.name())
                        .isNotNull(OutboxEvent::getSentAt)
                        .lt(OutboxEvent::getSentAt, cutoff)
                        .last("LIMIT " + BATCH_SIZE)));

        return new Deleted(consumed, outbox);
    }

    private int deleteInBatches(IntSupplier batch) {
        int deleted = 0;
        for (int round = 0; round < MAX_BATCHES_PER_DATABASE; round++) {
            int rows = batch.getAsInt();
            deleted += rows;
            if (rows < BATCH_SIZE) {
                break;
            }
        }
        return deleted;
    }

    private List<String> scanTargets() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(TenantRoutingContext.CONTROL);
        keys.addAll(schemaCatalog.provisionedSchemas().values());
        return List.copyOf(keys);
    }

    /** 两张表各删了多少（日志要分开看：哪张在长、长多少，是调保留期的依据）。 */
    record Deleted(int consumed, int outbox) {

        Deleted plus(Deleted other) {
            return new Deleted(consumed + other.consumed, outbox + other.outbox);
        }

        int total() {
            return consumed + outbox;
        }
    }
}
