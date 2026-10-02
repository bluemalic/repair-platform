package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.converter.TicketConverter;
import com.bluemalic.repair.dto.TicketArriveDTO;
import com.bluemalic.repair.dto.TicketCreateDTO;
import com.bluemalic.repair.dto.TicketEvaluateDTO;
import com.bluemalic.repair.dto.TicketFinishDTO;
import com.bluemalic.repair.dto.TicketRejectDTO;
import com.bluemalic.repair.dto.TicketReworkDTO;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.service.TicketFlowService;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.vo.TicketDetailVO;
import com.bluemalic.repair.vo.TicketVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工单流转域实现：submit 到 close 的全部状态跃迁。从 TicketServiceImpl 拆出（纯移动，
 * 改动建议 #4 第五步·收官）。
 *
 * <p>查询在 {@link TicketQueryService}、派单在 {@link TicketAssignmentService}、
 * 超时在 {@code TicketTimeoutHandler}；校验、条件更新、台账与通知复用
 * {@link TicketTransitionSupport}。学生可见自己的单、维修工见负责楼栋或派给
 * 自己的单、后勤限本租户——数据范围由拦截器注入（ADR-002）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketFlowServiceImpl implements TicketFlowService {

    private final TicketMapper ticketMapper;
    private final TicketEvaluationMapper ticketEvaluationMapper;
    private final NotificationService notificationService;
    private final TimeoutService timeoutService;
    private final TicketTransitionSupport transitions;

    public TicketVO submit(TicketCreateDTO dto) {
        long studentId = StpUtil.getLoginIdAsLong();
        SysUser student = transitions.requireUser(studentId);

        Long buildingId;
        String room;
        if (dto.getRepairCode() != null && !dto.getRepairCode().isBlank()) {
            RepairCode repairCode = transitions.requireRepairCode(dto.getRepairCode(), student.getTenantId());
            buildingId = repairCode.getBuildingId();
            room = repairCode.getRoom();
        } else {
            if (dto.getBuildingId() == null || dto.getRoom() == null || dto.getRoom().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "报修码与楼栋房间至少提供一项");
            }
            buildingId = dto.getBuildingId();
            room = dto.getRoom();
        }
        // 楼栋校验不能只放在上面那个分支里：扫码路径同样要过（码指向的楼栋可能已被停用）
        transitions.requireEnabledBuilding(buildingId, student.getTenantId());

        TicketCategory category = transitions.requireEnabledCategory(dto.getCategoryId(), student.getTenantId());

        Ticket ticket = new Ticket();
        ticket.setTenantId(student.getTenantId());
        ticket.setTicketNo(transitions.nextTicketNo());
        ticket.setStudentId(studentId);
        ticket.setBuildingId(buildingId);
        ticket.setRoom(room);
        ticket.setCategoryId(dto.getCategoryId());
        ticket.setDescription(dto.getDescription());
        ticket.setImages(dto.getImages());
        ticket.setUrgency(dto.getUrgency() == null ? category.getDefaultUrgency() : dto.getUrgency());
        ticket.setStatus(TicketStatus.TO_DISPATCH.getCode());
        ticket.setSubmitTime(LocalDateTime.now());
        ticketMapper.insert(ticket);

        transitions.writeLog(ticket.getTenantId(), ticket.getId(), null, TicketStatus.TO_DISPATCH.getCode(),
                TicketAction.SUBMIT, studentId, null);
        log.info("提交报修 ticketId={} studentId={} building={} room={}",
                ticket.getId(), studentId, buildingId, room);

        return TicketConverter.toVO(ticket,
                transitions.buildingNames(List.of(buildingId)), transitions.categoryNames(List.of(dto.getCategoryId())));
    }
    public void cancel(long id) {
        long studentId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.CANCELED.getCode());

        // 学生只能撤自己的单——数据范围由拦截器注入到 UPDATE 里，这里不再手写 student_id 条件
        transitions.conditionalUpdate(id, TicketStatus.CANCELED.getCode(), TicketAction.CANCEL, studentId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> entity.setCloseTime(LocalDateTime.now()));
    }
    public void rework(long id, TicketReworkDTO dto) {
        long studentId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        if (!ticket.getStudentId().equals(studentId)) {
            throw new BizException(ErrorCode.TICKET_NOT_YOURS);
        }
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.PROCESSING.getCode());

        // 理由进 ticket_log 的备注（时间线上看得见），**不新增列**：它和"驳回理由"是两件事，
        // 塞进同一列会让两个动作的语义糊在一起（docs/01 §4.1 记了取舍）
        transitions.conditionalUpdate(id, TicketStatus.PROCESSING.getCode(), TicketAction.REWORK, studentId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> { }, dto.getReason());
        // 回到 30 处理中：重新登记"未处理升级"（基准仍是派单时间，口径不变）。
        // 40 待验收本身不挂任何超时节点，所以这里 cancel 只是防御：万一将来给它挂了节点也不会漏撤
        timeoutService.cancel(id);
        timeoutService.registerProcess(id, ticket.getDispatchTime());
        transitions.notifyTransition(ticket, TicketAction.REWORK, null, "验收不通过：" + dto.getReason());
        log.info("验收不通过，打回重做 ticketId={} studentId={} reason={}", id, studentId, dto.getReason());
    }
    public void evaluate(long id, TicketEvaluateDTO dto) {
        long studentId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        if (!ticket.getStudentId().equals(studentId)) {
            throw new BizException(ErrorCode.TICKET_NOT_YOURS);
        }
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.FINISHED.getCode());

        TicketEvaluation evaluation = new TicketEvaluation();
        evaluation.setTenantId(ticket.getTenantId());
        evaluation.setTicketId(id);
        evaluation.setStudentId(studentId);
        evaluation.setScore(dto.getScore());
        evaluation.setContent(dto.getContent());
        try {
            ticketEvaluationMapper.insert(evaluation);
        } catch (DuplicateKeyException e) {
            // uk_ticket 唯一索引兜底：并发下的重复评价到这里变成明确的业务错误
            throw new BizException(ErrorCode.TICKET_ALREADY_EVALUATED);
        }

        transitions.conditionalUpdate(id, TicketStatus.FINISHED.getCode(), TicketAction.EVALUATE, studentId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> { });
        transitions.notifyTransition(ticket, TicketAction.EVALUATE, null, "已被评价 " + dto.getScore() + " 分");
        // 进入 50 已完成：登记验收超时（默认 24h，到期仍未人工关闭则自动流转 60）
        timeoutService.registerEval(ticket.getId());
    }
    public void accept(long id) {
        long workerId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.PROCESSING.getCode());
        if (transitions.isNotAssignee(ticket, workerId)) {
            // 状态对但不是派给我的 → 并发抢单场景
            throw new BizException(ErrorCode.TICKET_ALREADY_ACCEPTED);
        }

        // where 带上 worker_id = 当前人 + status = 旧状态：被别人抢先时 rows = 0
        transitions.conditionalUpdate(id, TicketStatus.PROCESSING.getCode(), TicketAction.ACCEPT, workerId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus())
                        .eq(Ticket::getWorkerId, workerId),
                entity -> entity.setAcceptTime(LocalDateTime.now()));
        // 接单节点完成：撤掉未接单提醒；同时登记"未处理升级"。
        // 基准传派单时间（不是现在）：需求口径是"从派到完工"整体超期，与兜底扫描同基准
        timeoutService.cancel(id);
        timeoutService.registerProcess(id, ticket.getDispatchTime());
        transitions.notifyTransition(ticket, TicketAction.ACCEPT, null, null);
    }
    public void arrive(long id, TicketArriveDTO dto) {
        long workerId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        if (ticket.getStatus() != TicketStatus.PROCESSING.getCode()) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED, "工单不在处理中，无法到场打卡");
        }
        // 到场/完工对**参与人**开放：主责或协作者（docs/01 §4.5）。
        // 接单与驳回仍只给主责——那两件事是"我认领这单"和"这单不该我做"，属于处置权
        if (transitions.isNotParticipant(ticket, workerId)) {
            throw new BizException(ErrorCode.TICKET_ALREADY_ACCEPTED);
        }
        // 扫码到场的关键校验：码对应的位置必须和工单一致，防止"人没到先打卡"
        RepairCode repairCode = transitions.requireRepairCode(dto.getRepairCode(), ticket.getTenantId());
        if (!repairCode.getBuildingId().equals(ticket.getBuildingId())
                || !repairCode.getRoom().equals(ticket.getRoom())) {
            throw new BizException(ErrorCode.REPAIR_CODE_ROOM_MISMATCH);
        }
        if (ticket.getArriveTime() != null) {
            // 幂等：已经打过卡就不再覆盖首次时间
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        // 到场不改变状态（30 → 30），只记录时间并折算响应时长 = 到场 − 派单
        Integer arriveMinutes = ticket.getDispatchTime() != null
                ? (int) Duration.between(ticket.getDispatchTime(), now).toMinutes() : null;
        transitions.conditionalUpdate(id, ticket.getStatus(), TicketAction.ARRIVE, workerId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> {
                    wrapper.eq(Ticket::getStatus, ticket.getStatus())
                            .isNull(Ticket::getArriveTime);
                    appendParticipantCondition(wrapper, workerId);
                },
                entity -> {
                    entity.setArriveTime(now);
                    entity.setArriveMinutes(arriveMinutes);
                });
        transitions.notifyTransition(ticket, TicketAction.ARRIVE, null, null);
    }
    public void finish(long id, TicketFinishDTO dto) {
        long workerId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.TO_VERIFY.getCode());
        if (transitions.isNotParticipant(ticket, workerId)) {
            throw new BizException(ErrorCode.TICKET_ALREADY_ACCEPTED);
        }

        LocalDateTime now = LocalDateTime.now();
        Integer handleMinutes = ticket.getArriveTime() != null
                ? (int) Duration.between(ticket.getArriveTime(), now).toMinutes() : null;
        transitions.conditionalUpdate(id, TicketStatus.TO_VERIFY.getCode(), TicketAction.FINISH, workerId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> {
                    wrapper.eq(Ticket::getStatus, ticket.getStatus());
                    appendParticipantCondition(wrapper, workerId);
                },
                entity -> {
                    entity.setFinishTime(now);
                    entity.setResultDesc(dto.getResultDesc());
                    entity.setResultImages(dto.getResultImages());
                    entity.setHandleMinutes(handleMinutes);
                });
        // 完工：处理节点完成，撤掉未处理升级登记（后续由验收超时节点接管）
        timeoutService.cancel(id);
        transitions.notifyTransition(ticket, TicketAction.FINISH, null, null);
        // 协作者完成的：主责得知道自己的单被完成了（他不会收到学生那条通知）
        if (ticket.getWorkerId() != null && ticket.getWorkerId() != workerId) {
            notificationService.send(ticket.getTenantId(), ticket.getWorkerId(), "TICKET_FINISHED_BY_COLLABORATOR",
                    "工单已由协作者完工",
                    "工单 " + ticket.getTicketNo() + " 已由 " + transitions.nullToEmpty(transitions.requireUser(workerId).getRealName())
                            + " 完工，等待学生验收",
                    ticket.getId());
        }
    }
    public void rejectByWorker(long id, TicketRejectDTO dto) {
        long workerId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.REJECTED.getCode());
        if (transitions.isNotAssignee(ticket, workerId)) {
            throw new BizException(ErrorCode.TICKET_ALREADY_ACCEPTED);
        }
        doReject(ticket, workerId, dto.getReason());
        transitions.notifyTransition(ticket, TicketAction.REJECT, null, "被驳回：" + dto.getReason());
    }
    public void rejectByAdmin(long id, TicketRejectDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.REJECTED.getCode());
        doReject(ticket, adminId, dto.getReason());
        transitions.notifyTransition(ticket, TicketAction.REJECT, ticket.getStudentId(), "被驳回：" + dto.getReason());
        transitions.notifyTransition(ticket, TicketAction.REJECT, ticket.getWorkerId(), "被驳回：" + dto.getReason());
    }
    void doReject(Ticket ticket, long operatorId, String reason) {
        transitions.conditionalUpdate(ticket.getId(), TicketStatus.REJECTED.getCode(), TicketAction.REJECT, operatorId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> {
                    entity.setRejectReason(reason);
                    // 驳回后清空维修工，重新派单时另指派
                    entity.setWorkerId(null);
                });
        // 驳回后原节点作废（可能是未接单提醒，也可能是待验收的验收超时）；重新派单会重新登记
        timeoutService.cancel(ticket.getId());
        log.info("驳回工单 ticketId={} operator={} reason={}", ticket.getId(), operatorId, reason);
    }
    public void close(long id) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.CLOSED.getCode());

        transitions.conditionalUpdate(id, TicketStatus.CLOSED.getCode(), TicketAction.CLOSE, adminId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> entity.setCloseTime(LocalDateTime.now()));
        // 人工关闭后取消已登记的验收超时任务，避免调度器重复处理
        timeoutService.cancel(id);
    }
    void appendParticipantCondition(LambdaUpdateWrapper<Ticket> wrapper, long workerId) {
        wrapper.and(w -> w.eq(Ticket::getWorkerId, workerId)
                .or().exists("SELECT 1 FROM ticket_collaborator c"
                        + " WHERE c.ticket_id = ticket.id AND c.worker_id = {0}", workerId));
    }
}
