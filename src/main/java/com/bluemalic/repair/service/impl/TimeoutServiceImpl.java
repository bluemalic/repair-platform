package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.TimeoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    /** 单轮兜底扫描上限：防止一次把历史上所有超期工单拉进内存（下一分钟继续处理剩下的）。 */
    private static final int SCAN_LIMIT = 200;

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
    public void registerProcess(long ticketId, LocalDateTime dispatchTime) {
        if (dispatchTime == null) {
            // 没派单时间就没法按需求口径算到期——交给每分钟的兜底扫描，别在这里用"现在"凑一个基准
            log.warn("工单缺少派单时间，跳过期升级登记（由兜底扫描接管） ticketId={}", ticketId);
            return;
        }
        registerProcessDeadline(ticketId, dispatchTime.plus(timeoutRule.processDuration())
                .atZone(ZoneId.systemDefault()).toInstant());
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
        return scanOverdueTickets(TicketStatus.TO_ACCEPT.getCode(), cutoff);
    }

    @Override
    public List<Long> backstopScanProcess() {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeoutRule.processDuration());
        return scanOverdueTickets(TicketStatus.PROCESSING.getCode(), cutoff);
    }

    /**
     * 兜底扫描与登记用同一个基准：评价时间（`ticket_evaluation.create_time`）超过阈值——
     * 而不是 finish_time，否则"完工很久才评价"的工单会被按完工时间提前判超时。
     *
     * <p>查询在 Mapper XML 里做了三件必须一起做的事（评审点出过缺了会随时间恶化）：
     * **JOIN ticket 限定 `status = 50`**（否则已关闭工单会被每分钟重复扫到）、
     * **只取主键**、**LIMIT**（单轮上限）。
     */
    @Override
    public List<Long> backstopScanEval() {
        LocalDateTime cutoff = LocalDateTime.now().minus(timeoutRule.evalDuration());
        return ticketEvaluationMapper.selectOverdueOpenTicketIds(cutoff, SCAN_LIMIT);
    }

    /**
     * 定时任务没有租户上下文（拦截器不注入条件），所以这里的查询天然跨租户——正是兜底想要的；
     * 但要自己带状态过滤与单轮上限：漏了状态过滤，N 会单调增长（每分钟 N 次无效查询 + N 行日志）。
     */
    private List<Long> scanOverdueTickets(int status, LocalDateTime cutoff) {
        Page<Ticket> page = ticketMapper.selectPage(new Page<>(1, SCAN_LIMIT, false),
                Wrappers.<Ticket>lambdaQuery()
                        .eq(Ticket::getStatus, status)
                        .lt(Ticket::getDispatchTime, cutoff)
                        .orderByAsc(Ticket::getDispatchTime)
                        .select(Ticket::getId));
        return page.getRecords().stream().map(Ticket::getId).toList();
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