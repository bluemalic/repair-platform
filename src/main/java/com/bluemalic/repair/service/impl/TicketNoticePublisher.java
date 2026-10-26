package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.NotificationCommand;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.service.OutboxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

import static java.util.Map.entry;

/**
 * 状态变更通知：动作 → 通知规格（类型、标题、正文后缀、默认接收方）收敛在这里，
 * 调用方只负责“触发”与必要的补充参数。
 */
@Component
class TicketNoticePublisher {

    private final NotificationService notificationService;
    private final OutboxService outboxService;
    private final boolean mqDelivery;

    TicketNoticePublisher(NotificationService notificationService,
                          OutboxService outboxService,
                          @Value("${repair.notification.delivery:sync}") String delivery) {
        this.notificationService = notificationService;
        this.outboxService = outboxService;
        this.mqDelivery = "mq".equalsIgnoreCase(delivery);
    }

    /** 状态变更通知的规格：类型、标题、正文后缀、默认接收方。新动作加一行即可。 */
    record NoticeSpec(String type, String title, String suffix, boolean toStudent) {
    }

    private static final Map<TicketAction, NoticeSpec> TRANSITION_NOTICE = Map.ofEntries(
            entry(TicketAction.ACCEPT,   new NoticeSpec("TICKET_ACCEPTED",  "维修工已接单",     "已被接单，维修工会尽快到场", true)),
            entry(TicketAction.ARRIVE,   new NoticeSpec("TICKET_ARRIVED",   "维修工已到场",     "维修工已到场处理",           true)),
            entry(TicketAction.WAIT_MATERIAL, new NoticeSpec("TICKET_WAITING_MATERIAL", "维修暂停（待料）",
                    "因缺少材料暂停，材料到货后继续", true)),
            entry(TicketAction.UNREPAIRABLE, new NoticeSpec("TICKET_UNREPAIRABLE", "维修工判定无法维修",
                    "无法维修，后勤会跟进处理", true)),
            entry(TicketAction.RESUME,   new NoticeSpec("TICKET_RESUMED",   "工单恢复处理",     "已恢复处理",                 true)),
            entry(TicketAction.FINISH,   new NoticeSpec("TICKET_FINISHED",  "维修完成待验收",   "已完成维修，请验收评价",     true)),
            entry(TicketAction.REJECT,   new NoticeSpec("TICKET_REJECTED",  "工单被驳回",       null,                          true)),
            entry(TicketAction.DISPATCH, new NoticeSpec("TICKET_DISPATCHED","新工单待接单",     "已派给你，请及时接单",       false)),
            entry(TicketAction.EVALUATE, new NoticeSpec("TICKET_EVALUATED", "工单已验收",       null,                          false)),
            // 验收不通过：发给维修工（toStudent=false → 接收人取 ticket.workerId）
            entry(TicketAction.REWORK,   new NoticeSpec("TICKET_REWORKED",  "验收不通过",       null,                          false)),
            // 转派：发给原师傅（toStudent=false → 接收人取转派前的 ticket.workerId）
            entry(TicketAction.TRANSFER, new NoticeSpec("TICKET_TRANSFERRED", "工单已转出",      null,                          false)),
            // 撤单：发给当前师傅；10/80 没有师傅，接收人为空时静默跳过
            entry(TicketAction.CANCEL,   new NoticeSpec("TICKET_CANCELED",  "工单已撤销",       null,                          false)),
            // 合并：发给被合并单的学生，正文由调用方带上目标工单号
            entry(TicketAction.MERGE,    new NoticeSpec("TICKET_MERGED",    "工单已合并",       null,                          true)),
            entry(TicketAction.CLOSE,    new NoticeSpec("TICKET_CLOSED",    "工单已关闭",       "已关闭",                      true)),
            entry(TicketAction.AUTO_CLOSE, new NoticeSpec("TICKET_AUTO_CLOSED", "工单已自动关闭", "验收后超时未关闭，工单已自动关闭", true)));

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
        String content = "工单 " + ticket.getTicketNo() + (suffix == null ? spec.suffix() : suffix);
        if (mqDelivery) {
            // 与状态变更同一个本地事务：通知事件先落 Outbox，再异步投递；
            // 这样业务提交了，通知命令一定在，MQ/消费者失败只会重试。
            outboxService.append(ticket.getTenantId(), "TICKET", ticket.getId(), NotificationCommand.EVENT_TYPE,
                    new NotificationCommand(ticket.getTenantId(), receiver, spec.type(),
                            spec.title(), content, ticket.getId()));
            return;
        }
        notificationService.send(ticket.getTenantId(), receiver, spec.type(), spec.title(), content, ticket.getId());
    }
}
