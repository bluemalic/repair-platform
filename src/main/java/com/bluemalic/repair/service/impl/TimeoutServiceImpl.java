package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.TimeoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时调度机制（ADR-001 落地）。ZSet：key = ticket:timeout:{节点}，member = ticketId，
 * score = 到期毫秒时间戳。消费时先 ZREM，返回 1 的实例才处理 → 多实例天然去重。
 *
 * <p>注意"系统上下文"语义：调度器没有登录态，查询/更新都不会被数据权限拦截器注入条件
 * （handler 对 null loginId 直接返回 null），所以能跨租户处理——这正是兜底扫描想要的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutServiceImpl implements TimeoutService {

    private static final String KEY_PREFIX = "ticket:timeout:";
    private static final String NODE_ACCEPT = "ACCEPT";
    private static final String NODE_PROCESS = "PROCESS";
    private static final String NODE_EVAL = "EVAL";

    private final StringRedisTemplate stringRedisTemplate;
    private final TimeoutRule timeoutRule;
    private final TicketMapper ticketMapper;
    private final TicketEvaluationMapper ticketEvaluationMapper;

    @Override
    public void registerAccept(long ticketId) {
        registerAcceptDeadline(ticketId, Instant.now().plus(timeoutRule.acceptDuration()));
    }

    @Override
    public void registerAcceptDeadline(long ticketId, Instant deadline) {
        add(NODE_ACCEPT, ticketId, deadline);
    }

    @Override
    public void registerProcess(long ticketId) {
        registerProcessDeadline(ticketId, Instant.now().plus(timeoutRule.processDuration()));
    }

    @Override
    public void registerProcessDeadline(long ticketId, Instant deadline) {
        add(NODE_PROCESS, ticketId, deadline);
    }

    @Override
    public void registerEval(long ticketId) {
        registerEvalDeadline(ticketId, Instant.now().plus(timeoutRule.evalDuration()));
    }

    @Override
    public void registerEvalDeadline(long ticketId, Instant deadline) {
        add(NODE_EVAL, ticketId, deadline);
    }

    @Override
    public void cancel(long ticketId) {
        for (String node : List.of(NODE_ACCEPT, NODE_PROCESS, NODE_EVAL)) {
            stringRedisTemplate.opsForZSet().remove(key(node), String.valueOf(ticketId));
        }
    }

    @Override
    public List<Long> handleDueAccept() {
        return takeDue(NODE_ACCEPT);
    }

    @Override
    public List<Long> handleDueProcess() {
        return takeDue(NODE_PROCESS);
    }

    @Override
    public List<Long> handleDueEval() {
        return takeDue(NODE_EVAL);
    }

    @Override
    public List<Long> backstopScanAccept() {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeoutRule.acceptDuration());
        return ticketMapper.selectList(Wrappers.<Ticket>lambdaQuery()
                        .eq(Ticket::getStatus, TicketStatus.TO_ACCEPT.getCode())
                        .lt(Ticket::getDispatchTime, cutoff)
                        .select(Ticket::getId))
                .stream().map(Ticket::getId).toList();
    }

    @Override
    public List<Long> backstopScanProcess() {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeoutRule.processDuration());
        return ticketMapper.selectList(Wrappers.<Ticket>lambdaQuery()
                        .eq(Ticket::getStatus, TicketStatus.PROCESSING.getCode())
                        .lt(Ticket::getDispatchTime, cutoff)
                        .select(Ticket::getId))
                .stream().map(Ticket::getId).toList();
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

    private void add(String node, long ticketId, Instant deadline) {
        stringRedisTemplate.opsForZSet().add(key(node), String.valueOf(ticketId), deadline.toEpochMilli());
    }

    private List<Long> takeDue(String node) {
        String zsetKey = key(node);
        var zSet = stringRedisTemplate.opsForZSet();
        var due = zSet.rangeByScore(zsetKey, 0, System.currentTimeMillis());
        if (due == null || due.isEmpty()) {
            return List.of();
        }
        return due.stream()
                .filter(member -> {
                    // 只有 ZREM 成功的执行者才拿到这个任务（多实例唯一消费）
                    Long removed = zSet.remove(zsetKey, member);
                    return removed != null && removed > 0;
                })
                .map(Long::parseLong)
                .toList();
    }

    private String key(String node) {
        return KEY_PREFIX + node;
    }
}