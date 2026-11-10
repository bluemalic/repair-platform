package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.MetricsRecorder;
import com.bluemalic.repair.common.TextLimits;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.TicketStatusChangedEvent;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 工单状态流转的唯一写入点：条件更新 + 流转日志 + Outbox 事件 + 指标。
 *
 * <p>只保留“所有流转都必须一样”的部分；校验、通知、读取、参与者判定已经拆到
 * {@link TicketReferenceValidator} / {@link TicketAssignmentPolicy} /
 * {@link TicketParticipantPolicy} / {@link TicketNoticePublisher} /
 * {@link TicketNoGenerator} / {@link TicketReadModelAssembler} / {@link TicketLookup}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketTransitionSupport {

    private final TicketMapper ticketMapper;
    private final TicketLogMapper ticketLogMapper;
    private final MetricsRecorder metricsRecorder;
    private final OutboxService outboxService;

    /**
     * 条件更新：SET 来自 entitySetter 对实体的赋值（null 字段被 MP 跳过），
     * WHERE 来自 wrapper（含当前状态）。rows = 0 → 状态已被并发改动 → 20002。
     * 全部状态流转都从这里落库，顺带写 ticket_log 与 Outbox 事件。
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
     * @param logRemark 写进 {@code ticket_log.remark} 的说明（如“跨楼栋强制派单”）；为 null 时按
     *                  {@code entity.rejectReason} 兜底——这样驳回那条路径不用改，也不会因为
     *                  “备注从实体上取”而对别的动作产生副作用
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
        // 与业务更新、流转日志同一个本地事务：业务提交了，事件就一定在；
        // 事件写失败则整个流转回滚，不会留下“状态改了、事件丢了”。
        outboxService.append(tenantId, "TICKET", id, TicketStatusChangedEvent.EVENT_TYPE,
                new TicketStatusChangedEvent(id, fromStatus, toStatus, action.name(), operatorId));
        // 指标只在**落库成功之后**记：并发失败（rows=0 已抛异常）与非法跃迁都不该算"发生了一次"
        metricsRecorder.ticketEvent(action, tenantId == null ? 0L : tenantId);
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
        String safeRemark = TextLimits.truncate(remark, TextLimits.TICKET_LOG_REMARK);
        if (remark != null && safeRemark.length() < remark.length()) {
            log.warn("工单日志备注超长已截断 ticketId={} action={} originalLength={}",
                    ticketId, action, remark.length());
        }
        ticketLog.setRemark(safeRemark);
        ticketLogMapper.insert(ticketLog);
    }

    /** 同一工单是否已经写过某类超时动作（超时提醒的幂等判据）。 */
    boolean notifiedBefore(Long tenantId, long ticketId, TicketAction action) {
        return ticketLogMapper.selectCount(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTenantId, tenantId)
                .eq(TicketLog::getTicketId, ticketId)
                .eq(TicketLog::getAction, action.name())) > 0;
    }
}
