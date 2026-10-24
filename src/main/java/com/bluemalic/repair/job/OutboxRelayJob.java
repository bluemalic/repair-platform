package com.bluemalic.repair.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.OutboxStatus;
import com.bluemalic.repair.entity.OutboxEvent;
import com.bluemalic.repair.mapper.OutboxEventMapper;
import com.bluemalic.repair.service.OutboxEventHandler;
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
 * Outbox relay（阶段 1）：逐库扫描 {@code outbox_event}，claim 后交给本地 handler。
 *
 * <p><b>为什么逐库</b>：每个学校一个 database，一次 SQL 扫不到所有库；和
 * {@code TimeoutServiceImpl.scanTargets()} 同一套“平台库 + 每个已搬家租户库”的遍历方式。
 *
 * <p><b>多实例</b>：claim 用 {@code UPDATE ... WHERE status='NEW'}，只有影响行数为 1 的实例处理，
 * 因此同一事件不会被两个实例同时消费。代价是“claim 后进程崩溃”会留下 SENDING 行；
 * 阶段 2 接 MQ 时再加“超时 SENDING 回收”，现在先接受这个边界。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayJob {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_BACKOFF_SECONDS = 300;

    private final OutboxEventMapper outboxEventMapper;
    private final TenantSchemaCatalog schemaCatalog;
    private final List<OutboxEventHandler> handlers;

    /**
     * 测试里关掉定时触发；手动调 {@link #relayOnce()} 仍然可用。
     * 生产默认开启，如需临时停用设 {@code repair.outbox.relay-enabled=false}。
     */
    @Value("${repair.outbox.relay-enabled:true}")
    private boolean relayEnabled;

    @Scheduled(fixedDelayString = "${OUTBOX_RELAY_INTERVAL_MS:1000}")
    public void relay() {
        if (!relayEnabled) {
            return;
        }
        List<String> targets;
        try {
            targets = scanTargets();
        } catch (Exception e) {
            log.warn("Outbox relay 取库清单失败，本轮跳过：{}", e.getMessage());
            return;
        }
        for (String routingKey : targets) {
            try {
                TenantRoutingContext.run(routingKey, this::relayOnce);
            } catch (Exception e) {
                // 一个库失败不影响其它库；下一轮继续
                log.warn("Outbox relay 处理库失败 routingKey={} err={}", routingKey, e.getMessage());
            }
        }
    }

    private List<String> scanTargets() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(TenantRoutingContext.CONTROL);
        keys.addAll(schemaCatalog.provisionedSchemas().values());
        return List.copyOf(keys);
    }

    /**
     * 处理当前路由键对应库里的到期事件。包级可见是为了让测试直接驱动，
     * 不用等 {@code @Scheduled} 的 1 秒 tick。
     */
    void relayOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> due = outboxEventMapper.selectList(Wrappers.<OutboxEvent>lambdaQuery()
                .eq(OutboxEvent::getStatus, OutboxStatus.NEW.name())
                .le(OutboxEvent::getNextAttemptAt, now)
                .orderByAsc(OutboxEvent::getId)
                .last("LIMIT " + BATCH_SIZE));
        for (OutboxEvent event : due) {
            if (!claim(event)) {
                continue;
            }
            try {
                OutboxEventHandler handler = handlers.stream()
                        .filter(candidate -> candidate.supports(event.getEventType()))
                        .findFirst()
                        .orElse(null);
                if (handler == null) {
                    log.debug("Outbox 暂无消费者，直接标记已投递 eventId={} type={}",
                            event.getEventId(), event.getEventType());
                } else {
                    handler.handle(event);
                }
                markSent(event);
            } catch (Exception e) {
                markRetry(event, e);
            }
        }
    }

    private boolean claim(OutboxEvent event) {
        int rows = outboxEventMapper.update(null, Wrappers.<OutboxEvent>lambdaUpdate()
                .eq(OutboxEvent::getId, event.getId())
                .eq(OutboxEvent::getStatus, OutboxStatus.NEW.name())
                .set(OutboxEvent::getStatus, OutboxStatus.SENDING.name()));
        return rows == 1;
    }

    private void markSent(OutboxEvent event) {
        outboxEventMapper.update(null, Wrappers.<OutboxEvent>lambdaUpdate()
                .eq(OutboxEvent::getId, event.getId())
                .set(OutboxEvent::getStatus, OutboxStatus.SENT.name())
                .set(OutboxEvent::getSentAt, LocalDateTime.now()));
    }

    private void markRetry(OutboxEvent event, Exception e) {
        int attempts = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
        long backoffSeconds = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempts, 8));
        outboxEventMapper.update(null, Wrappers.<OutboxEvent>lambdaUpdate()
                .eq(OutboxEvent::getId, event.getId())
                .set(OutboxEvent::getStatus, OutboxStatus.NEW.name())
                .set(OutboxEvent::getAttempts, attempts)
                // 同样截到秒：DATETIME 的舍入不该把退避时间再往后推 1 秒
                .set(OutboxEvent::getNextAttemptAt,
                        LocalDateTime.now().plusSeconds(backoffSeconds).withNano(0)));
        log.warn("Outbox 事件投递失败，{} 秒后重试 eventId={} attempts={}",
                backoffSeconds, event.getEventId(), attempts, e);
    }
}
