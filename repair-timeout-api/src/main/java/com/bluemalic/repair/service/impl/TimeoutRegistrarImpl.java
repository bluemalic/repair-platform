package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.config.TimeoutProperties;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.mq.TimeoutMqScheduler;
import com.bluemalic.repair.service.TimeoutKeys;
import com.bluemalic.repair.service.TimeoutRegistrar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 超时登记的实现（阶段 3.4 · ADR-035）。
 *
 * <p>两种投递方式，二选一：
 * <ul>
 *   <li>{@code zset}（默认）：写 Redis ZSet，单体/超时进程秒级轮询；</li>
 *   <li>{@code mq}：发 RocketMQ 定时消息（{@code setDeliverTimeMs}），由 timeout 进程消费。</li>
 * </ul>
 * 两者都不需要"超时进程"在场——这正是把它独立成模块的目的：工单域自己就能登记。
 *
 * <p><b>为什么 mq 模式缺 producer 要抛错</b>：说明 {@code repair.timeout.delivery=mq} 与
 * {@code repair.rocketmq.enabled} 配得不一致。启动时从第一条超时才炸，排查成本高得多。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutRegistrarImpl implements TimeoutRegistrar {

    private final StringRedisTemplate stringRedisTemplate;

    private final TimeoutRule timeoutRule;

    /** zset（默认，单体自轮询）/ mq（RocketMQ 定时消息，timeout-service 执行）—— 阶段 2 · ADR-024。 */
    private final TimeoutProperties timeoutProperties;

    /** mq 模式才装配；用 ObjectProvider 是为了让 zset 模式不依赖 RocketMQ 的任何 Bean。 */
    private final ObjectProvider<TimeoutMqScheduler> mqSchedulerProvider;

    @Override
    public void registerAccept(long ticketId, long tenantId) {
        registerAcceptDeadline(ticketId, tenantId, Instant.now().plus(timeoutRule.acceptDuration()));
    }

    @Override
    public void registerAcceptDeadline(long ticketId, long tenantId, Instant deadline) {
        add(TimeoutKeys.NODE_ACCEPT, tenantId, ticketId, deadline);
    }

    @Override
    public void registerProcess(long ticketId, long tenantId, LocalDateTime dispatchTime) {
        if (dispatchTime == null) {
            // 没派单时间就没法按需求口径算到期——交给每分钟的兜底扫描，别在这里用"现在"凑一个基准
            log.warn("工单缺少派单时间，跳过期升级登记（由兜底扫描接管）ticketId={}", ticketId);
            return;
        }
        registerProcessDeadline(ticketId, tenantId, dispatchTime.plus(timeoutRule.processDuration())
                .atZone(ZoneId.systemDefault()).toInstant());
    }

    @Override
    public void registerProcessDeadline(long ticketId, long tenantId, Instant deadline) {
        add(TimeoutKeys.NODE_PROCESS, tenantId, ticketId, deadline);
    }

    @Override
    public void registerEval(long ticketId, long tenantId) {
        registerEvalDeadline(ticketId, tenantId, Instant.now().plus(timeoutRule.evalDuration()));
    }

    @Override
    public void registerEvalDeadline(long ticketId, long tenantId, Instant deadline) {
        add(TimeoutKeys.NODE_EVAL, tenantId, ticketId, deadline);
    }

    @Override
    public void cancel(long ticketId, long tenantId) {
        if (timeoutProperties.isMqDelivery()) {
            // RocketMQ 定时消息无法撤回：到期消息执行前会按状态 CAS + ticket_log 判重跳过，
            // 所以这里只做"逻辑取消"，不再删除任何东西。
            log.debug("MQ 模式下超时为逻辑取消 ticketId={} tenantId={}", ticketId, tenantId);
            return;
        }
        for (String node : List.of(TimeoutKeys.NODE_ACCEPT, TimeoutKeys.NODE_PROCESS, TimeoutKeys.NODE_EVAL)) {
            String key = TimeoutKeys.key(node);
            stringRedisTemplate.opsForZSet().remove(key, TimeoutKeys.member(tenantId, ticketId));
            // 过渡期老格式（成员只有工单 ID）也清一下：它们消费时会被状态判定跳过，
            // 但留着就是白跑一趟
            stringRedisTemplate.opsForZSet().remove(key, String.valueOf(ticketId));
        }
    }

    private void add(String node, long tenantId, long ticketId, Instant deadline) {
        if (timeoutProperties.isMqDelivery()) {
            TimeoutMqScheduler scheduler = mqSchedulerProvider.getIfAvailable();
            if (scheduler == null) {
                // delivery=mq 但 RocketMQ 未开启/调度器未装配：启动就该失败，不要等第一条超时才炸
                throw new IllegalStateException(
                        "repair.timeout.delivery=mq 但 MQ 调度器未装配（检查 repair.rocketmq.enabled）");
            }
            scheduler.schedule(node, ticketId, tenantId, deadline);
            return;
        }
        stringRedisTemplate.opsForZSet().add(TimeoutKeys.key(node),
                TimeoutKeys.member(tenantId, ticketId), deadline.toEpochMilli());
    }
}
