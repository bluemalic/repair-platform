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
 * 因此同一事件不会被两个实例同时消费。claim 会同时写一个 {@value #CLAIM_LEASE_SECONDS} 秒的租约
 * （推进 {@code next_attempt_at}）：进程在投递前崩溃时，租约到期后由 {@link #reclaimExpiredClaims()}
 * 把行放回 NEW，事件不会永远卡在 SENDING。代价是“投递耗时超过租约”时可能重复投递——
 * 这是 at-least-once，消费者按 eventId 幂等，重复投递不会变成重复通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayJob {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_BACKOFF_SECONDS = 300;

    /**
     * claim 的租约（秒）：claim 时把 {@code next_attempt_at} 推到这个时间点，作为
     * “这行正在被某个实例处理”的标记。必须明显长于一次投递的耗时（MQ send 是秒级），
     * 否则正常投递中的行会被误回收。
     */
    private static final long CLAIM_LEASE_SECONDS = 60;

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
        // 先回收：否则崩溃留下的 SENDING 行永远不会再被看到（既不投递、也不重试、没有日志）
        reclaimExpiredClaims();
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
                .set(OutboxEvent::getStatus, OutboxStatus.SENDING.name())
                // 租约：到期前这行不会被视为“可投递”，到期后由 reclaim 放回 NEW
                .set(OutboxEvent::getNextAttemptAt,
                        LocalDateTime.now().plusSeconds(CLAIM_LEASE_SECONDS).withNano(0)));
        return rows == 1;
    }

    /**
     * 回收“claim 后进程崩溃”留下的 SENDING 行，把它们放回 NEW。
     *
     * <p>回收同时累加 attempts 并按退避公式推迟下一次尝试：如果某条事件每次都让投递进程崩溃
     * （毒消息），至少重试间隔会逐步拉长到上限，而且每轮都会留下 WARN。
     */
    private void reclaimExpiredClaims() {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> expired = outboxEventMapper.selectList(Wrappers.<OutboxEvent>lambdaQuery()
                .eq(OutboxEvent::getStatus, OutboxStatus.SENDING.name())
                .le(OutboxEvent::getNextAttemptAt, now)
                .orderByAsc(OutboxEvent::getId)
                .last("LIMIT " + BATCH_SIZE));
        for (OutboxEvent event : expired) {
            int attempts = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
            int rows = outboxEventMapper.update(null, Wrappers.<OutboxEvent>lambdaUpdate()
                    .eq(OutboxEvent::getId, event.getId())
                    .eq(OutboxEvent::getStatus, OutboxStatus.SENDING.name())
                    // 租约值一起做条件：另一个实例刚好回收并重新 claim 时，不能把它的新租约覆盖掉
                    .eq(OutboxEvent::getNextAttemptAt, event.getNextAttemptAt())
                    .set(OutboxEvent::getStatus, OutboxStatus.NEW.name())
                    .set(OutboxEvent::getAttempts, attempts)
                    .set(OutboxEvent::getNextAttemptAt,
                            now.plusSeconds(backoffSeconds(attempts)).withNano(0)));
            if (rows == 1) {
                log.warn("回收超时 SENDING 事件（通常是投递进程在 claim 后崩溃）eventId={} attempts={}",
                        event.getEventId(), attempts);
            }
        }
    }

    /** 指数退避，封顶 {@value #MAX_BACKOFF_SECONDS} 秒。 */
    private long backoffSeconds(int attempts) {
        return Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempts, 8));
    }

    private void markSent(OutboxEvent event) {
        outboxEventMapper.update(null, Wrappers.<OutboxEvent>lambdaUpdate()
                .eq(OutboxEvent::getId, event.getId())
                .set(OutboxEvent::getStatus, OutboxStatus.SENT.name())
                .set(OutboxEvent::getSentAt, LocalDateTime.now()));
    }

    private void markRetry(OutboxEvent event, Exception e) {
        int attempts = (event.getAttempts() == null ? 0 : event.getAttempts()) + 1;
        long backoffSeconds = backoffSeconds(attempts);
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
