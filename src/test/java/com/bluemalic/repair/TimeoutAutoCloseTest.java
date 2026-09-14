package com.bluemalic.repair;

import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.mapper.NotificationMapper;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.service.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验收超时自动关闭（M3 第一步）：50 已完成 超阈值（默认 24h）未人工关闭 → 自动流转 60。
 *
 * <p>覆盖两条触发路径（ADR-001）：
 * <ul>
 *   <li>ZSet 延迟队列消费（到期任务 → handleDueEval → autoClose）</li>
 *   <li>每分钟兜底扫描（Redis 重启丢任务 → backstopScanEval → autoClose）</li>
 * </ul>
 * 以及幂等：处理过的不重复关、不重复通知。
 *
 * <p>测试不走 @Scheduled 线程（那会读不到未提交数据），直接调用服务方法模拟调度器的动作；
 * 阈值用真实默认（24h），靠回填过去的时间构造"已到期"数据。
 */
@IntegrationTest
class TimeoutAutoCloseTest {

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketLogMapper ticketLogMapper;

    @Autowired
    private TicketEvaluationMapper ticketEvaluationMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private TimeoutService timeoutService;

    @Autowired
    private TicketService ticketService;

    private final List<Long> createdTickets = new ArrayList<>();

    @AfterEach
    void cleanZSetMembers() {
        // 测试自身写入的 ZSet 成员不随事务回滚，逐个取消，避免影响其他测试与真实调度器
        createdTickets.forEach(timeoutService::cancel);
    }

    @Test
    void dueDeadlineIsConsumedAndAutoClosed() {
        Ticket ticket = ticketAt(50, LocalDateTime.now().minusHours(25), LocalDateTime.now().minusHours(25));
        createdTickets.add(ticket.getId());
        timeoutService.registerEvalDeadline(ticket.getId(), Instant.now().minusSeconds(60));

        // 模拟调度器：消费到期成员 → 逐个执行 autoClose
        List<Long> due = timeoutService.handleDueEval();
        due.forEach(id -> ticketService.autoClose(id));

        Ticket closed = ticketMapper.selectById(ticket.getId());
        assertThat(closed.getStatus()).isEqualTo(60);
        assertThat(closed.getCloseTime()).isNotNull();
        assertLogAction(ticket.getId(), TicketAction.AUTO_CLOSE);
        long notices = notificationMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Notification>lambdaQuery()
                        .eq(Notification::getReceiverId, ticket.getStudentId())
                        .eq(Notification::getType, "TICKET_AUTO_CLOSED"));
        assertThat(notices).isEqualTo(1);

        // 幂等：再消费一次不产生第二条日志/通知（成员已被 ZREM、状态已是 60）
        timeoutService.handleDueEval().forEach(id -> ticketService.autoClose(id));
        assertLogCount(ticket.getId(), TicketAction.AUTO_CLOSE, 1);
    }

    @Test
    void futureOrAlreadyClosedTicketsAreSkipped() {
        Ticket notDue = ticketAt(50, LocalDateTime.now().minusHours(25), LocalDateTime.now().minusHours(25));
        createdTickets.add(notDue.getId());
        timeoutService.registerEvalDeadline(notDue.getId(), Instant.now().plusSeconds(3600));
        timeoutService.handleDueEval().forEach(id -> ticketService.autoClose(id));
        assertThat(ticketMapper.selectById(notDue.getId()).getStatus()).isEqualTo(50);

        Ticket alreadyClosed = ticketAt(60, LocalDateTime.now().minusHours(25), LocalDateTime.now().minusHours(25));
        createdTickets.add(alreadyClosed.getId());
        timeoutService.registerEvalDeadline(alreadyClosed.getId(), Instant.now().minusSeconds(60));
        // 与调度器行为一致：成员被消费，但对 60 执行 autoClose 会抛"状态不允许"，被捕获跳过
        timeoutService.handleDueEval().forEach(id -> {
            try {
                ticketService.autoClose(id);
            } catch (com.bluemalic.repair.common.BizException expected) {
                assertThat(id).isEqualTo(alreadyClosed.getId());
            }
        });
        assertThat(ticketMapper.selectById(alreadyClosed.getId()).getStatus()).isEqualTo(60);
        assertLogCount(alreadyClosed.getId(), TicketAction.AUTO_CLOSE, 0);
    }

    @Test
    void backstopFindsOverdueEvaluations() {
        Ticket overdue = ticketAt(50, LocalDateTime.now().minusHours(25), LocalDateTime.now().minusHours(25));
        createdTickets.add(overdue.getId());
        givenEvaluation(overdue, LocalDateTime.now().minusHours(25)); // 评价已超期
        Ticket fresh = ticketAt(50, LocalDateTime.now().minusHours(1), LocalDateTime.now().minusHours(1));
        createdTickets.add(fresh.getId());
        givenEvaluation(fresh, LocalDateTime.now().minusMinutes(30)); // 评价未超期

        List<Long> due = timeoutService.backstopScanEval();
        assertThat(due).contains(overdue.getId()).doesNotContain(fresh.getId());

        // 模拟调度器：对兜底结果逐个执行 autoClose（autoClose 内部校验状态仍为 50）
        due.forEach(id -> ticketService.autoClose(id));
        assertThat(ticketMapper.selectById(overdue.getId()).getStatus()).isEqualTo(60);
        assertThat(ticketMapper.selectById(fresh.getId()).getStatus()).isEqualTo(50);
    }

    // ==================== 工具 ====================

    private Ticket ticketAt(int status, LocalDateTime submitAt, LocalDateTime finishAt) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(1L);
        ticket.setTicketNo("TAC" + System.nanoTime());
        ticket.setStudentId(900000L); // 不必是真实用户：通知接收人只是落库字段
        ticket.setBuildingId(1L);
        ticket.setRoom("1-101");
        ticket.setCategoryId(1L);
        ticket.setUrgency(1);
        ticket.setStatus(status);
        ticket.setSubmitTime(submitAt);
        ticket.setDispatchTime(finishAt.minusHours(20));
        ticket.setAcceptTime(finishAt.minusHours(19));
        ticket.setArriveTime(finishAt.minusHours(3));
        ticket.setFinishTime(finishAt);
        if (status == 60) {
            ticket.setCloseTime(LocalDateTime.now());
        }
        ticketMapper.insert(ticket);
        return ticket;
    }

    private void givenEvaluation(Ticket ticket, LocalDateTime createdAt) {
        TicketEvaluation evaluation = new TicketEvaluation();
        evaluation.setTenantId(ticket.getTenantId());
        evaluation.setTicketId(ticket.getId());
        evaluation.setStudentId(ticket.getStudentId());
        evaluation.setScore(5);
        evaluation.setContent("超时测试");
        evaluation.setCreateTime(createdAt);
        ticketEvaluationMapper.insert(evaluation);
    }

    private void assertLogAction(Long ticketId, TicketAction action) {
        assertThat(ticketLogMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TicketLog>lambdaQuery()
                        .eq(TicketLog::getTicketId, ticketId)
                        .eq(TicketLog::getAction, action.name()))).hasSize(1);
    }

    private void assertLogCount(Long ticketId, TicketAction action, int expected) {
        Long count = ticketLogMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TicketLog>lambdaQuery()
                        .eq(TicketLog::getTicketId, ticketId)
                        .eq(TicketLog::getAction, action.name()));
        assertThat(count).isEqualTo(expected);
    }
}