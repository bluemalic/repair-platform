package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 状态变更通知：动作 → 通知规格（类型、标题、正文后缀、默认接收方）收敛在这里，
 * 调用方只负责“触发”与必要的补充参数。
 */
@Component
@RequiredArgsConstructor
class TicketNoticePublisher {

    private final NotificationService notificationService;

    /** 状态变更通知的规格：类型、标题、正文后缀、默认接收方。新动作加一行即可。 */
    record NoticeSpec(String type, String title, String suffix, boolean toStudent) {
    }

    private static final Map<TicketAction, NoticeSpec> TRANSITION_NOTICE = Map.of(
            TicketAction.ACCEPT,   new NoticeSpec("TICKET_ACCEPTED",  "维修工已接单",     "已被接单，维修工会尽快到场", true),
            TicketAction.ARRIVE,   new NoticeSpec("TICKET_ARRIVED",   "维修工已到场",     "维修工已到场处理",           true),
            TicketAction.FINISH,   new NoticeSpec("TICKET_FINISHED",  "维修完成待验收",   "已完成维修，请验收评价",     true),
            TicketAction.REJECT,   new NoticeSpec("TICKET_REJECTED",  "工单被驳回",       null,                          true),
            TicketAction.DISPATCH, new NoticeSpec("TICKET_DISPATCHED","新工单待接单",     "已派给你，请及时接单",       false),
            TicketAction.EVALUATE, new NoticeSpec("TICKET_EVALUATED", "工单已验收",       null,                          false),
            // 验收不通过：发给维修工（toStudent=false → 接收人取 ticket.workerId）
            TicketAction.REWORK,   new NoticeSpec("TICKET_REWORKED",  "验收不通过",       null,                          false),
            // 转派：发给原师傅（toStudent=false → 接收人取转派前的 ticket.workerId）
            TicketAction.TRANSFER, new NoticeSpec("TICKET_TRANSFERRED", "工单已转出",      null,                          false),
            TicketAction.AUTO_CLOSE, new NoticeSpec("TICKET_AUTO_CLOSED", "工单已自动关闭", "验收后超时未关闭，工单已自动关闭", true));

    /**
     * 按动作给相关方发站内通知。默认接收方取自工单上的学生/维修工；
     * explicitReceiver 非空时优先——派单时工单还没写维修工、管理员驳回时维修工已被清空，
     * 这两种场景由调用方把“该收通知的人”传进来，而不是事后回查。
     */
    void notifyTransition(Ticket ticket, TicketAction action, Long explicitReceiver, String suffix) {
        NoticeSpec spec = TRANSITION_NOTICE.get(action);
        if (spec == null) {
            return;
        }
        Long receiver = explicitReceiver != null ? explicitReceiver
                : (spec.toStudent() ? ticket.getStudentId() : ticket.getWorkerId());
        if (receiver == null || receiver <= 0) {
            // 转派 / 管理员驳回时 worker_id 可能已被清空：没有接收人就静默跳过，
            // 不要用 long 拆箱换一个 500。
            return;
        }
        notificationService.send(ticket.getTenantId(), receiver, spec.type(), spec.title(),
                "工单 " + ticket.getTicketNo() + (suffix == null ? spec.suffix() : suffix), ticket.getId());
    }
}
