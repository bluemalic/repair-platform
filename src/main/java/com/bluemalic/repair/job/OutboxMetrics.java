package com.bluemalic.repair.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.OutboxStatus;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.mapper.OutboxEventMapper;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 积压指标（ADR-020 的延续）：{@code NEW} 事件总数 + 最老一条的年龄。
 *
 * <p><b>为什么需要它</b>：relay 成功/失败只看日志，Grafana 上只有“发生了多少业务动作”，
 * 回答不了“现在有多少事件还没投出去、最老的卡了多久”。这两个值才是 Outbox 链路的健康度。
 *
 * <p><b>为什么 30 秒刷新而不是每次 scrape 回库</b>：Prometheus 每 15 秒抓一次，如果 Gauge
 * 直接查库，监控本身就会把数据库变成高频查询源。这里用定时刷新 + AtomicLong，抓取只读内存。
 * 刷新失败保留上一次的值（不会因为某个库暂时连不上就掉成 0，那会被误读成“没有积压”）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMetrics {

    private final OutboxEventMapper outboxEventMapper;
    private final TenantSchemaCatalog schemaCatalog;
    private final MeterRegistry meterRegistry;

    /** 测试里关掉定时刷新（{@code @IntegrationTest} 已设 false）；手动调 {@link #refresh()} 仍可用。 */
    @Value("${repair.outbox.metrics-enabled:true}")
    private boolean enabled;

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();

    @PostConstruct
    void register() {
        Gauge.builder("repair.outbox.pending", pending, AtomicLong::doubleValue)
                .description("状态为 NEW 的 Outbox 事件总数（所有库）")
                .register(meterRegistry);
        Gauge.builder("repair.outbox.oldest_age_seconds", oldestAgeSeconds, AtomicLong::doubleValue)
                .description("最老一条 NEW 事件已积压的秒数；没有积压为 0")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${repair.outbox.metrics-interval-ms:30000}")
    public void scheduledRefresh() {
        if (!enabled) {
            return;
        }
        refresh();
    }

    /** 包级可见：测试直接驱动，不受 enabled 开关影响。 */
    void refresh() {
        long totalPending = 0;
        LocalDateTime oldest = null;
        for (String routingKey : scanTargets()) {
            try {
                Backlog backlog = TenantRoutingContext.call(routingKey, this::backlogInCurrentDatabase);
                totalPending += backlog.pending();
                if (backlog.oldest() != null && (oldest == null || backlog.oldest().isBefore(oldest))) {
                    oldest = backlog.oldest();
                }
            } catch (Exception e) {
                // 一个库失败不影响其它库；保留上一次的值，避免被误读成"没有积压"
                log.debug("Outbox 指标刷新失败 routingKey={} err={}", routingKey, e.getMessage());
            }
        }
        pending.set(totalPending);
        oldestAgeSeconds.set(oldest == null
                ? 0
                : Math.max(0, Duration.between(oldest, LocalDateTime.now()).getSeconds()));
    }

    private Backlog backlogInCurrentDatabase() {
        long count = outboxEventMapper.selectCount(Wrappers.<OutboxEvent>lambdaQuery()
                .eq(OutboxEvent::getStatus, OutboxStatus.NEW.name()));
        LocalDateTime oldest = null;
        if (count > 0) {
            List<OutboxEvent> rows = outboxEventMapper.selectList(Wrappers.<OutboxEvent>lambdaQuery()
                    .eq(OutboxEvent::getStatus, OutboxStatus.NEW.name())
                    .orderByAsc(OutboxEvent::getCreatedAt)
                    .select(OutboxEvent::getCreatedAt)
                    .last("LIMIT 1"));
            if (!rows.isEmpty()) {
                oldest = rows.get(0).getCreatedAt();
            }
        }
        return new Backlog(count, oldest);
    }

    private List<String> scanTargets() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(TenantRoutingContext.CONTROL);
        keys.addAll(schemaCatalog.provisionedSchemas().values());
        return List.copyOf(keys);
    }

    private record Backlog(long pending, LocalDateTime oldest) {
    }
}
