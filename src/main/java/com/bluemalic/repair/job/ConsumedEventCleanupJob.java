package com.bluemalic.repair.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.ConsumedEvent;
import com.bluemalic.repair.mapper.ConsumedEventMapper;
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

/**
 * {@code consumed_event} 保留期清理（阶段 2 · ADR-023）。
 *
 * <p><b>为什么必须清理</b>：这是追加型表，每条被消费的消息都留一行。不清理就是无限增长，
 * 而它上面有 {@code uk_consumer_event} 唯一索引——索引越大，每次消费的插入越慢，
 * 最终拖住的是**消息消费本身**，而且这种退化是渐进的，不会有任何报错提示。
 *
 * <p><b>保留期取多长</b>：只要覆盖 MQ 的最大重投窗口即可。RocketMQ 默认只保留 72 小时消息，
 * 超过这个时间的重复投递不会发生；默认 30 天有充足余量，也留出了人工排查消费记录的时间。
 *
 * <p><b>逐库 + 分批</b>：和 {@code OutboxRelayJob} 同一套遍历方式（平台库 + 每个已搬家租户库），
 * 一次 DELETE 扫不到所有库。每个库每批 {@value #BATCH_SIZE} 行、最多
 * {@value #MAX_BATCHES_PER_DATABASE} 批，避免一条大 DELETE 长时间持锁挡住消费写入；
 * 单轮删不完的留到下一次，不会堆积（每天产生的量远小于单轮上限）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsumedEventCleanupJob {

    private static final int BATCH_SIZE = 1000;
    private static final int MAX_BATCHES_PER_DATABASE = 100;

    private final ConsumedEventMapper consumedEventMapper;
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
            // 配错成 0 或负数时宁可一行都不删：这个任务操作失误的后果是"删光幂等记录"，不可逆。
            log.warn("consumed_event 保留期配置非法（days={}），本轮不清理", retentionDays);
            return;
        }
        try {
            int deleted = cleanExpired(LocalDateTime.now().minusDays(retentionDays));
            if (deleted > 0) {
                log.info("consumed_event 保留期清理完成，共删除 {} 行（保留 {} 天）", deleted, retentionDays);
            }
        } catch (Exception e) {
            // 不向外抛：抛出去只会被调度器再打一遍；下次到点自然重试。
            // 一天只跑一次，失败必须留下完整堆栈，否则查不动。
            log.error("consumed_event 保留期清理失败（下个周期会重试）", e);
        }
    }

    /**
     * 删除所有库里 {@code consumedAt} 早于 cutoff 的行。包级可见是为了让测试直接驱动，
     * 不用等凌晨的 cron，也不受 {@code enabled} 开关影响。
     */
    int cleanExpired(LocalDateTime cutoff) {
        int deleted = 0;
        for (String routingKey : scanTargets()) {
            try {
                deleted += TenantRoutingContext.call(routingKey,
                        () -> deleteExpiredInCurrentDatabase(cutoff));
            } catch (Exception e) {
                // 一个库失败不影响其它库；下一轮继续
                log.warn("consumed_event 清理失败 routingKey={} err={}", routingKey, e.getMessage());
            }
        }
        return deleted;
    }

    private int deleteExpiredInCurrentDatabase(LocalDateTime cutoff) {
        int deleted = 0;
        for (int round = 0; round < MAX_BATCHES_PER_DATABASE; round++) {
            int rows = consumedEventMapper.delete(Wrappers.<ConsumedEvent>lambdaQuery()
                    .lt(ConsumedEvent::getConsumedAt, cutoff)
                    .last("LIMIT " + BATCH_SIZE));
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
}
