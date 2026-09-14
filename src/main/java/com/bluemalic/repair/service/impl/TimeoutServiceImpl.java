package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.service.TimeoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时调度机制（ADR-001 落地）。ZSet：key = ticket:timeout，member = {ticketId}:{节点}，
 * score = 到期毫秒时间戳。消费时先 ZREM，返回 1 的实例才处理 → 多实例天然去重。
 *
 * <p>注意"系统上下文"语义：调度器没有登录态，查询/更新都不会被数据权限拦截器注入条件
 * （handler 对 null loginId 直接返回 null），所以能跨租户处理——这正是兜底扫描想要的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutServiceImpl implements TimeoutService {

    private static final String KEY = "ticket:timeout";
    private static final String NODE_EVAL = "EVAL";

    private final StringRedisTemplate stringRedisTemplate;
    private final TimeoutRule timeoutRule;
    private final TicketEvaluationMapper ticketEvaluationMapper;

    @Override
    public void registerEval(long ticketId) {
        registerEvalDeadline(ticketId, Instant.now().plus(timeoutRule.evalDuration()));
    }

    @Override
    public void registerEvalDeadline(long ticketId, Instant deadline) {
        stringRedisTemplate.opsForZSet().add(KEY, member(ticketId), deadline.toEpochMilli());
    }

    @Override
    public void cancel(long ticketId) {
        stringRedisTemplate.opsForZSet().remove(KEY, member(ticketId));
    }

    @Override
    public List<Long> handleDueEval() {
        var zSet = stringRedisTemplate.opsForZSet();
        var due = zSet.rangeByScore(KEY, 0, System.currentTimeMillis());
        if (due == null || due.isEmpty()) {
            return List.of();
        }
        return due.stream()
                .filter(member -> {
                    // 只有 ZREM 成功的执行者才拿到这个任务（多实例唯一消费）
                    Long removed = zSet.remove(KEY, member);
                    return removed != null && removed > 0;
                })
                .map(this::ticketIdOf)
                .toList();
    }

    /**
     * 兜底扫描与登记用同一个基准：评价时间（ticket_evaluation.create_time）超过阈值——
     * 而不是 finish_time，否则"完工很久才评价"的工单会被按完工时间提前判超时。
     * 查到的是"评价已超期"的工单，处理交由 autoClose（内部再校验状态必须还是 50）。
     */
    @Override
    public List<Long> backstopScanEval() {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeoutRule.evalDuration());
        return ticketEvaluationMapper.selectList(Wrappers.<TicketEvaluation>lambdaQuery()
                        .lt(TicketEvaluation::getCreateTime, cutoff)
                        .select(TicketEvaluation::getTicketId))
                .stream().map(TicketEvaluation::getTicketId).toList();
    }

    private String member(long ticketId) {
        return ticketId + ":" + NODE_EVAL;
    }

    private long ticketIdOf(String member) {
        return Long.parseLong(member.substring(0, member.indexOf(':')));
    }
}