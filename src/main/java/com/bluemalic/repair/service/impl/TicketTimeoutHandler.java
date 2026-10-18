package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.service.DueTicket;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超时域处理器：三个超时回调——自动关闭（50 → 60）、接单提醒、处理超时升级。
 *
 * <p>从 TicketServiceImpl 拆出（纯移动，改动建议 #4 第四步）。唯一消费者是
 * {@code TimeoutScheduler}（秒级消费 + 每分钟兜底扫描都汇到这三个方法），
 * 跑在系统上下文（操作者 = 0），没有 HTTP 端点；相关测试直接调用本类。
 *
 * <p>幂等与"是否还该提醒/升级"的判定在 {@code notifyTimeoutOnce}（状态 + ticket_log 判重），
 * 到期与否由调度器判定——职责边界见各方法的调用方注释。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketTimeoutHandler {

    private static final long SYSTEM_OPERATOR = 0L;

    /** 超时提醒类通知（不是状态跃迁，所以不进 TRANSITION_NOTICE 表）：类型与标题放一处。 */
    private static final String NOTICE_ACCEPT_TIMEOUT = "TICKET_ACCEPT_TIMEOUT";
    private static final String TITLE_ACCEPT_TIMEOUT = "工单超时未接单";
    private static final String NOTICE_PROCESS_TIMEOUT = "TICKET_PROCESS_TIMEOUT";
    private static final String TITLE_PROCESS_TIMEOUT = "工单处理超时升级";

    private final TicketTransitionSupport transitions;
    private final NotificationService notificationService;
    private final TimeoutService timeoutService;
    private final TimeoutRule timeoutRule;
    private final TenantSchemaCatalog schemaCatalog;

    /**
     * 超时自动关闭：**按这条任务所属租户的库**处理（阶段 6 第 3 步 · ADR-021）。
     *
     * <p>工单、流转日志、通知、审计都在租户自己的库里，所以整段处理必须跑在"那个库"的路由键下
     * ——这也是"租户库装完整一套"带来的好处：处理逻辑一个字都不用改。
     */
    public void autoClose(DueTicket due) {
        runPinned(due, () -> autoCloseInCurrentDatabase(due.ticketId()));
    }

    public void remindAcceptTimeout(DueTicket due) {
        runPinned(due, () -> remindAcceptTimeoutInCurrentDatabase(due.ticketId()));
    }

    public void escalateProcessTimeout(DueTicket due) {
        runPinned(due, () -> escalateProcessTimeoutInCurrentDatabase(due.ticketId()));
    }

    /**
     * 解析"这条任务现在该去哪个库"并钉到线程上。
     *
     * <p>解析走 {@code TenantSchemaCatalog}（而不是任务里的成员原样用）：成员是登记当时写下的，
     * 学校搬家之后旧值就不对了；目录里存的是**当前**答案，切换后立刻生效。
     * 没有租户信息的老成员（{@link DueTicket#LEGACY_TENANT}）按平台库处理——那批学校都还没搬家。
     */
    private void runPinned(DueTicket due, Runnable action) {
        String routingKey = due.tenantId() == DueTicket.LEGACY_TENANT
                ? TenantRoutingContext.CONTROL
                : schemaCatalog.routingKeyFor(due.tenantId());
        TenantRoutingContext.run(routingKey, action);
    }

    private void autoCloseInCurrentDatabase(long id) {
        Ticket ticket = transitions.requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.CLOSED.getCode());
        transitions.conditionalUpdate(id, TicketStatus.CLOSED.getCode(), TicketAction.AUTO_CLOSE, SYSTEM_OPERATOR,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> entity.setCloseTime(LocalDateTime.now()));
        transitions.notifyTransition(ticket, TicketAction.AUTO_CLOSE, null, null);
        timeoutService.cancel(id, ticket.getTenantId());
    }
    private void remindAcceptTimeoutInCurrentDatabase(long id) {
        Ticket ticket = transitions.requireTicket(id);
        notifyTimeoutOnce(ticket, TicketStatus.TO_ACCEPT.getCode(), TicketAction.ACCEPT_TIMEOUT,
                NOTICE_ACCEPT_TIMEOUT, TITLE_ACCEPT_TIMEOUT,
                "工单 " + ticket.getTicketNo() + " 已超过 " + timeoutRule.acceptThresholdText()
                        + " 无人接单，请及时调度");
    }
    private void escalateProcessTimeoutInCurrentDatabase(long id) {
        Ticket ticket = transitions.requireTicket(id);
        notifyTimeoutOnce(ticket, TicketStatus.PROCESSING.getCode(), TicketAction.PROCESS_TIMEOUT,
                NOTICE_PROCESS_TIMEOUT, TITLE_PROCESS_TIMEOUT,
                "工单 " + ticket.getTicketNo() + " 已超过 " + timeoutRule.processThresholdText()
                        + " 仍未完工，请及时跟进");
    }
    private void notifyTimeoutOnce(Ticket ticket, int expectedStatus, TicketAction action,
                                   String noticeType, String noticeTitle, String content) {
        if (ticket.getStatus() != expectedStatus) {
            // 已流转（接单/完工/驳回/关闭）——提醒没有意义，静默跳过
            return;
        }
        if (transitions.notifiedBefore(ticket.getTenantId(), ticket.getId(), action)) {
            return;
        }
        transitions.writeLog(ticket.getTenantId(), ticket.getId(), ticket.getStatus(), ticket.getStatus(),
                action, SYSTEM_OPERATOR, null);
        int sent = notificationService.sendToTenantAdmins(ticket.getTenantId(), noticeType,
                noticeTitle, content, ticket.getId());
        log.info("{} ticketId={} 送达后勤管理员 {} 人", action.getDesc(), ticket.getId(), sent);
    }
}
