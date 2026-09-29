package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.NotificationService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 状态流转的三件共享件：条件更新、流转日志、状态变更通知。
 *
 * <p>此前它们是 {@link TicketServiceImpl} 的私有方法，而状态流转本身横跨
 * 学生端 / 维修工端 / 后勤端三段——拆分的第一步先把这三件抽成包私有组件，
 * 让后面的按域拆分（Query / Assignment / Timeout / Flow）注入复用，
 * <b>而不是把"条件更新 + 写日志 + 发通知"的口径复制成多份</b>。
 *
 * <p>只做纯移动：方法体一字不改，行为等价由全量集成测试保证（全部走 HTTP 层，
 * 接口签名不变则测试无需改动）。
 */
@Component
@RequiredArgsConstructor
class TicketTransitionSupport {

    private final TicketMapper ticketMapper;
    private final TicketLogMapper ticketLogMapper;
    private final NotificationService notificationService;

    /** 状态变更通知的规格：类型、标题、正文后缀、默认接收方。满载等新动作加一行即可。 */
    record NoticeSpec(String type, String title, String suffix, boolean toStudent) {
    }

    /** 动作 → 通知规格。通知的文案与接收方收敛在这里，调用方只负责"触发"与必要的补充参数。 */
    private static final Map<TicketAction, NoticeSpec> TRANSITION_NOTICE = Map.of(
            TicketAction.ACCEPT,   new NoticeSpec("TICKET_ACCEPTED",  "维修工已接单",     "已被接单，维修工会尽快到场", true),
            TicketAction.ARRIVE,   new NoticeSpec("TICKET_ARRIVED",   "维修工已到场",     "维修工已到场处理",           true),
            TicketAction.FINISH,   new NoticeSpec("TICKET_FINISHED",  "维修完成待验收",   "已完成维修，请验收评价",     true),
            TicketAction.REJECT,   new NoticeSpec("TICKET_REJECTED",  "工单被驳回",       null,                          true),
            TicketAction.DISPATCH, new NoticeSpec("TICKET_DISPATCHED","新工单待接单",     "已派给你，请及时接单",       false),
            TicketAction.EVALUATE, new NoticeSpec("TICKET_EVALUATED", "工单已验收",       null,                          false),
            // 验收不通过：发给维修工（toStudent=false → 接收人取 ticket.workerId）
            TicketAction.REWORK,   new NoticeSpec("TICKET_REWORKED",  "验收不通过",       null,                          false),
            // 转派：发给**原师傅**（toStudent=false → 接收人取 ticket.workerId = 转派前的那个人）
            TicketAction.TRANSFER, new NoticeSpec("TICKET_TRANSFERRED", "工单已转出",      null,                          false),
            TicketAction.AUTO_CLOSE, new NoticeSpec("TICKET_AUTO_CLOSED", "工单已自动关闭", "验收后超时未关闭，工单已自动关闭", true));

    /**
     * 按动作给相关方发站内通知。默认接收方取自工单上的学生/维修工；
     * explicitReceiver 非空时优先——派单时工单还没写维修工、管理员驳回时维修工已被清空，
     * 这两种场景由调用方把"该收通知的人"传进来，而不是事后回查。
     */
    void notifyTransition(Ticket ticket, TicketAction action, Long explicitReceiver, String suffix) {
        NoticeSpec spec = TRANSITION_NOTICE.get(action);
        if (spec == null) {
            return;
        }
        long receiver = explicitReceiver != null ? explicitReceiver
                : (spec.toStudent() ? ticket.getStudentId() : ticket.getWorkerId());
        if (receiver <= 0) {
            return;
        }
        notificationService.send(ticket.getTenantId(), receiver, spec.type(), spec.title(),
                "工单 " + ticket.getTicketNo() + (suffix == null ? spec.suffix() : suffix), ticket.getId());
    }

    /**
     * 条件更新：SET 来自 entitySetter 对实体的赋值（null 字段被 MP 跳过），
     * WHERE 来自 wrapper（含当前状态）。rows = 0 → 状态已被并发改动 → 20002。
     * 全部状态流转都从这里落库，顺带写 ticket_log。
     *
     * <p>不需要额外备注的流转用这个重载：日志备注取 {@code entity.rejectReason}（目前只有驳回会设它）。
     */
    void conditionalUpdate(long id, int toStatus, TicketAction action, long operatorId,
                           Long tenantId, int fromStatus,
                           Consumer<LambdaUpdateWrapper<Ticket>> where,
                           Consumer<Ticket> entitySetter) {
        conditionalUpdate(id, toStatus, action, operatorId, tenantId, fromStatus, where, entitySetter, null);
    }

    /**
     * 带显式日志备注的重载。
     *
     * @param logRemark 写进 {@code ticket_log.remark} 的说明（如"跨楼栋强制派单"）；为 null 时按
     *                  {@code entity.rejectReason} 兜底——这样驳回那条路径不用改，也不会因为
     *                  "备注从实体上取"而对别的动作产生副作用
     */
    void conditionalUpdate(long id, int toStatus, TicketAction action, long operatorId,
                           Long tenantId, int fromStatus,
                           Consumer<LambdaUpdateWrapper<Ticket>> where,
                           Consumer<Ticket> entitySetter, String logRemark) {
        Ticket entity = new Ticket();
        entity.setStatus(toStatus);
        entitySetter.accept(entity);
        LambdaUpdateWrapper<Ticket> wrapper = new LambdaUpdateWrapper<Ticket>()
                .eq(Ticket::getId, id);
        where.accept(wrapper);
        int rows = ticketMapper.update(entity, wrapper);
        if (rows == 0) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED);
        }
        writeLog(tenantId, id, fromStatus, toStatus, action, operatorId,
                logRemark != null ? logRemark : entity.getRejectReason());
    }

    void writeLog(Long tenantId, long ticketId, Integer fromStatus, int toStatus,
                  TicketAction action, long operatorId, String remark) {
        TicketLog ticketLog = new TicketLog();
        ticketLog.setTenantId(tenantId);
        ticketLog.setTicketId(ticketId);
        ticketLog.setFromStatus(fromStatus);
        ticketLog.setToStatus(toStatus);
        ticketLog.setAction(action.name());
        ticketLog.setOperatorId(operatorId);
        ticketLog.setRemark(remark);
        ticketLogMapper.insert(ticketLog);
    }
}
