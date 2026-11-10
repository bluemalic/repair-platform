package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.Texts;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.converter.TicketConverter;
import com.bluemalic.repair.dto.TicketCollaboratorDTO;
import com.bluemalic.repair.dto.TicketDispatchDTO;
import com.bluemalic.repair.dto.TicketMergeDTO;
import com.bluemalic.repair.dto.TicketSplitDTO;
import com.bluemalic.repair.dto.TicketTransferDTO;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCollaborator;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketCollaboratorMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.service.TicketAssignmentService;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.vo.TicketCollaboratorVO;
import com.bluemalic.repair.vo.TicketVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单派单域实现：派单、转派、协作者管理、拆单。从 TicketServiceImpl 拆出（纯移动）。
 *
 * <p>五类动作都由后勤触发；目标校验与跨楼栋留痕在 {@link TicketAssignmentPolicy}，
 * 参与者判定在 {@link TicketParticipantPolicy}，流转写入/日志/Outbox 在
 * {@link TicketTransitionSupport}，通知在 {@link TicketNoticePublisher}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TicketAssignmentServiceImpl implements TicketAssignmentService {

    private final TicketMapper ticketMapper;
    private final TicketCollaboratorMapper ticketCollaboratorMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
    private final TimeoutService timeoutService;
    private final TicketTransitionSupport transitions;
    private final TicketLookup lookup;
    private final TicketReferenceValidator referenceValidator;
    private final TicketAssignmentPolicy assignmentPolicy;
    private final TicketParticipantPolicy participantPolicy;
    private final TicketNoticePublisher noticePublisher;
    private final TicketNoGenerator ticketNoGenerator;
    private final TicketReadModelAssembler readModelAssembler;

    /**
     * 一单最多几个协作者（`docs/01` §4.5）。**再多就不叫"搭把手"了**——那种情况该看是不是该拆单。
     * 这个上限不是并发的硬保证（两个请求同时加可能都通过检查），但唯一索引挡住了重复，
     * 最多多出一个人；为它加锁不值得，写在这里免得下次被当成 bug 查。
     */
    private static final int MAX_COLLABORATORS = 3;

    /** 能拆单的状态：活还没干完才谈得上"拆"（40 之后已经修完了，见 `docs/01` §4.5）。 */
    private static final List<Integer> SPLITTABLE_STATUSES = List.of(
            TicketStatus.TO_DISPATCH.getCode(), TicketStatus.TO_ACCEPT.getCode(), TicketStatus.PROCESSING.getCode());

    public void dispatch(long id, TicketDispatchDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = lookup.requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.TO_ACCEPT.getCode());

        SysUser worker = assignmentPolicy.requireEnabledWorker(dto.getWorkerId(), ticket.getTenantId());
        // 已有师傅的单不能走"派单"：那是**换人**，要走转派——它要重置计时、清掉上一轮的
        // 接单/到场时间、并单独留一条 TRANSFER 台账。两条路都能换人的话，台账就分不清了。
        // 例外是 45 无法维修：师傅已经明确"修不了"，后勤重新派给**别人**是标准处置
        if (ticket.getWorkerId() != null && ticket.getStatus() != TicketStatus.UNREPAIRABLE.getCode()) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED, "该工单已有维修工，换人请用转派");
        }
        if (ticket.getStatus() == TicketStatus.UNREPAIRABLE.getCode()
                && worker.getId().equals(ticket.getWorkerId())) {
            // 派回给原师傅等于把 dispatch_time 重置、让计时重来；要让他继续修，走"恢复处理"
            throw new BizException(ErrorCode.PARAM_INVALID, "重新派单必须换人；让原师傅继续修请用恢复处理");
        }

        // 跨楼栋派单 = 紧急抽调（docs/01 §4.2）：**放行**，但要留痕——为什么允许见 §4.2，
        // 为的是不让"派错楼栋"变成一张没人能操作的单（被派的人按『派给我的单』看得到、能处理）
        String crossBuilding = assignmentPolicy.crossBuildingTrace(ticket.getTenantId(), ticket.getBuildingId(), worker.getId());

        transitions.conditionalUpdate(id, TicketStatus.TO_ACCEPT.getCode(), TicketAction.DISPATCH, adminId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> {
                    wrapper.eq(Ticket::getStatus, ticket.getStatus());
                    if (ticket.getStatus() == TicketStatus.UNREPAIRABLE.getCode()) {
                        // 45 是从 30 转过来的，上一轮的接单/到场时间属于原师傅；
                        // 新师傅是新的一轮，不清掉会让他的到场打卡被幂等判断跳过
                        wrapper.set(Ticket::getAcceptTime, null)
                                .set(Ticket::getArriveTime, null)
                                .set(Ticket::getArriveMinutes, null);
                    }
                },
                entity -> {
                    entity.setWorkerId(dto.getWorkerId());
                    entity.setDispatchType(1);
                    entity.setDispatchTime(LocalDateTime.now());
                },
                crossBuilding);
        noticePublisher.notifyTransition(ticket, TicketAction.DISPATCH, dto.getWorkerId(), null);
        // 登记未接单提醒：到期仍无人接单就提醒调度方（M3）；重新派单会覆盖到期时间
        timeoutService.registerAccept(id, ticket.getTenantId());
        log.info("派单 ticketId={} workerId={} operator={} {}", id, dto.getWorkerId(), adminId,
                crossBuilding == null ? "" : crossBuilding);
    }
    public void transfer(long id, TicketTransferDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = lookup.requireTicket(id);
        // 只有"已经派出去、还没验收"的单才谈得上换人：
        // 10 还没派（走 dispatch）、80 已驳回（走 dispatch 重新派）、40 及之后活已干完（走打回/驳回）。
        int fromStatus = ticket.getStatus();
        if (fromStatus != TicketStatus.TO_ACCEPT.getCode()
                && fromStatus != TicketStatus.PROCESSING.getCode()) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    TicketStatus.of(fromStatus).getDesc() + "的工单不能转派");
        }

        SysUser worker = assignmentPolicy.requireEnabledWorker(dto.getWorkerId(), ticket.getTenantId());
        if (worker.getId().equals(ticket.getWorkerId())) {
            // 转给同一个人的唯一效果是把 dispatch_time 往后推——那是一条绕过"24h 未接单提醒"的路
            throw new BizException(ErrorCode.PARAM_INVALID, "新维修工与当前维修工相同，不需要转派");
        }
        Long previousWorkerId = ticket.getWorkerId();
        String crossBuilding = assignmentPolicy.crossBuildingTrace(ticket.getTenantId(), ticket.getBuildingId(), worker.getId());
        String remark = "转派给 " + worker.getRealName()
                + (crossBuilding == null ? "" : "；" + crossBuilding)
                + "；原因：" + dto.getReason();

        transitions.conditionalUpdate(id, TicketStatus.TO_ACCEPT.getCode(), TicketAction.TRANSFER, adminId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> {
                    wrapper.eq(Ticket::getStatus, ticket.getStatus());
                    if (previousWorkerId != null) {
                        // 转派是 20→20 / 30→20，状态 CAS 可能"永远成立"；
                        // 把转派前的主责写进 WHERE，并发/重试时只有一次能成功
                        wrapper.eq(Ticket::getWorkerId, previousWorkerId);
                    }
                    // 上一轮的接单/到场时间必须清掉：响应时长口径是"到场 − 派单"，
                    // 留着旧值会算出负数或虚高的数。谁来过在 ticket_log 里，不丢
                    wrapper.set(Ticket::getAcceptTime, null)
                            .set(Ticket::getArriveTime, null)
                            .set(Ticket::getArriveMinutes, null);
                },
                entity -> {
                    entity.setWorkerId(worker.getId());
                    entity.setDispatchType(1);
                    // 计时从头开始：不能让新师傅背前一个人的延迟
                    entity.setDispatchTime(LocalDateTime.now());
                },
                remark);
        // 通知原师傅（他的活没了，得知道为什么）：NoticeSpec(toStudent=false) 取的就是 ticket.workerId，
        // 而这时的 ticket 还是转派前的对象
        noticePublisher.notifyTransition(ticket, TicketAction.TRANSFER, null,
                "已转给 " + worker.getRealName() + "；原因：" + dto.getReason());
        // 通知新师傅：与派单同一条文案
        noticePublisher.notifyTransition(ticket, TicketAction.DISPATCH, worker.getId(), null);
        // 计时重来，超时节点也要重登记（先撤掉旧节点的登记，避免按旧时间触发）
        timeoutService.cancel(id, ticket.getTenantId());
        timeoutService.registerAccept(id, ticket.getTenantId());
        log.info("转派 ticketId={} from={} to={} operator={} reason={}",
                id, previousWorkerId, worker.getId(), adminId, dto.getReason());
    }

    public void resume(long id) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = lookup.requireTicket(id);
        int fromStatus = ticket.getStatus();
        if (fromStatus != TicketStatus.WAITING_MATERIAL.getCode()
                && fromStatus != TicketStatus.UNREPAIRABLE.getCode()) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    TicketStatus.of(fromStatus).getDesc() + "的工单不能恢复处理");
        }
        transitions.conditionalUpdate(id, TicketStatus.PROCESSING.getCode(), TicketAction.RESUME,
                adminId, ticket.getTenantId(), fromStatus,
                wrapper -> wrapper.eq(Ticket::getStatus, fromStatus),
                entity -> { });
        // 45 进来时撤过处理节点；35 没撤（待料不暂停 SLA）。两种情况都按**原派单时间**重登记：
        // 已经超期就立即升级——不能因为等后勤拍板把计时清零
        timeoutService.registerProcess(id, ticket.getTenantId(), ticket.getDispatchTime());
        noticePublisher.notifyTransition(ticket, TicketAction.RESUME, null, null);
        log.info("后勤恢复处理 ticketId={} operator={} fromStatus={}", id, adminId, fromStatus);
    }

    public void merge(long id, TicketMergeDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket source = lookup.requireTicket(id);
        int fromStatus = source.getStatus();
        // 状态机就是白名单：10/20/30/35/40/45/80 → 75；50/60/70/75 会被 20002 挡下
        TicketStatus.checkTransition(fromStatus, TicketStatus.MERGED.getCode());
        if (dto.getTargetId().equals(id)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能把工单合并到自己");
        }
        // 目标单必须在本租户可见范围内（跨租户与不存在对外都是 20001）
        Ticket target = lookup.requireTicket(dto.getTargetId());
        if (target.getStatus() == TicketStatus.CANCELED.getCode()
                || target.getStatus() == TicketStatus.MERGED.getCode()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "目标工单已撤单或已合并，不能作为合并目标");
        }
        String remark = "合并到 " + target.getTicketNo()
                + (dto.getRemark() == null || dto.getRemark().isBlank() ? "" : "；说明：" + dto.getRemark());

        transitions.conditionalUpdate(id, TicketStatus.MERGED.getCode(), TicketAction.MERGE, adminId,
                source.getTenantId(), fromStatus,
                wrapper -> {
                    wrapper.eq(Ticket::getStatus, fromStatus);
                    // MP 跳过实体里的 null 字段：清维修工必须显式写在 wrapper 上
                    wrapper.set(Ticket::getWorkerId, null);
                },
                entity -> {
                    entity.setMergedIntoId(dto.getTargetId());
                    entity.setCloseTime(LocalDateTime.now());
                },
                remark);
        // 合并是终态：未接单/处理超时/验收超时节点全部作废
        timeoutService.cancel(id, source.getTenantId());
        // 通知被合并单的学生（正文里带目标工单号）；目标单不动
        noticePublisher.notifyTransition(source, TicketAction.MERGE, null, remark);
        log.info("合并工单 ticketId={} into={} operator={} fromStatus={}",
                id, target.getId(), adminId, fromStatus);
    }

    public void addCollaborator(long id, TicketCollaboratorDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = lookup.requireTicket(id);
        requireCollaboratingStatus(ticket, "加协作者");
        // 与派单同一条校验：本租户、启用中的维修工（跨租户与"不存在"对外是同一个错误）
        SysUser worker = assignmentPolicy.requireEnabledWorker(dto.getWorkerId(), ticket.getTenantId());
        if (worker.getId().equals(ticket.getWorkerId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "他就是这单的主责师傅，不用再加成协作者");
        }
        if (participantPolicy.isCollaborator(ticket, worker.getId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该师傅已经是这单的协作者");
        }
        if (participantPolicy.collaboratorCount(ticket) >= MAX_COLLABORATORS) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "一单最多 " + MAX_COLLABORATORS + " 个协作者；活再多就该考虑拆单了");
        }

        TicketCollaborator collaborator = new TicketCollaborator();
        collaborator.setTenantId(ticket.getTenantId());
        collaborator.setTicketId(id);
        collaborator.setWorkerId(worker.getId());
        try {
            ticketCollaboratorMapper.insert(collaborator);
        } catch (DuplicateKeyException e) {
            // uk_ticket_worker 兜底：并发下两次加同一个人到这里变成明确的业务错误
            throw new BizException(ErrorCode.PARAM_INVALID, "该师傅已经是这单的协作者");
        }

        transitions.writeLog(ticket.getTenantId(), id, ticket.getStatus(), ticket.getStatus(),
                TicketAction.ADD_COLLABORATOR, adminId, "协作者：" + Texts.nullToEmpty(worker.getRealName()));
        notifyCollaboratorChange(ticket, worker, true);
        log.info("加协作者 ticketId={} workerId={} operator={}", id, worker.getId(), adminId);
    }
    public void removeCollaborator(long id, long workerId) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = lookup.requireTicket(id);
        requireCollaboratingStatus(ticket, "移除协作者");

        int rows = ticketCollaboratorMapper.delete(Wrappers.<TicketCollaborator>lambdaQuery()
                .eq(TicketCollaborator::getTenantId, ticket.getTenantId())
                .eq(TicketCollaborator::getTicketId, id)
                .eq(TicketCollaborator::getWorkerId, workerId));
        if (rows == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该师傅不是这单的协作者");
        }

        SysUser worker = sysUserMapper.selectById(workerId);
        transitions.writeLog(ticket.getTenantId(), id, ticket.getStatus(), ticket.getStatus(),
                TicketAction.REMOVE_COLLABORATOR, adminId,
                "移除协作者：" + (worker == null ? String.valueOf(workerId) : Texts.nullToEmpty(worker.getRealName())));
        if (worker != null) {
            // 通知当事人：他刚失去这张单的可见范围，不告诉他，他会照旧去现场
            notifyCollaboratorChange(ticket, worker, false);
        }
        log.info("移除协作者 ticketId={} workerId={} operator={}", id, workerId, adminId);
    }
    public TicketVO split(long id, TicketSplitDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = lookup.requireTicket(id);
        if (ticket.getParentTicketId() != null) {
            // 只拆一层：拆出来的单再拆下去会变成一棵谁都说不清的树（docs/01 §4.5）
            throw new BizException(ErrorCode.PARAM_INVALID, "拆出来的工单不能再拆");
        }
        if (!SPLITTABLE_STATUSES.contains(ticket.getStatus())) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    TicketStatus.of(ticket.getStatus()).getDesc() + "的工单不能拆单");
        }
        Long categoryId = dto.getCategoryId() == null ? ticket.getCategoryId() : dto.getCategoryId();
        referenceValidator.requireEnabledCategory(categoryId, ticket.getTenantId());

        Ticket created = new Ticket();
        created.setTenantId(ticket.getTenantId());
        created.setTicketNo(ticketNoGenerator.nextTicketNo());
        created.setStudentId(ticket.getStudentId());
        created.setParentTicketId(ticket.getId());
        // 楼栋 / 房间 / 图片继承原单：拆出来的那件事与原来那件在同一处、由同一个学生报的，
        // 现场照片往往一张里就有两处问题。**能改的只有"这件事本身是什么"**（见 TicketSplitDTO）
        created.setBuildingId(ticket.getBuildingId());
        created.setRoom(ticket.getRoom());
        created.setCategoryId(categoryId);
        created.setDescription(dto.getDescription());
        created.setImages(ticket.getImages());
        created.setUrgency(dto.getUrgency() == null ? ticket.getUrgency() : dto.getUrgency());
        created.setStatus(TicketStatus.TO_DISPATCH.getCode());
        created.setSubmitTime(LocalDateTime.now());
        ticketMapper.insert(created);

        // 两张单各记一条：原单说"我拆出了谁"、新单说"我从哪来"——各自的时间线都能自己解释自己
        transitions.writeLog(ticket.getTenantId(), id, ticket.getStatus(), ticket.getStatus(),
                TicketAction.SPLIT, adminId, "拆出工单 " + created.getTicketNo());
        transitions.writeLog(ticket.getTenantId(), created.getId(), null, TicketStatus.TO_DISPATCH.getCode(),
                TicketAction.SPLIT, adminId, "由工单 " + ticket.getTicketNo() + " 拆出");
        notifySplit(ticket, created);
        log.info("拆单 sourceTicketId={} newTicketId={} operator={}", id, created.getId(), adminId);

        return TicketConverter.toVO(created, readModelAssembler.buildingNames(List.of(created.getBuildingId())),
                readModelAssembler.categoryNames(List.of(created.getCategoryId())));
    }
    void requireCollaboratingStatus(Ticket ticket, String action) {
        int status = ticket.getStatus();
        if (status != TicketStatus.TO_ACCEPT.getCode() && status != TicketStatus.PROCESSING.getCode()) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    TicketStatus.of(status).getDesc() + "的工单不能" + action);
        }
    }
    void notifyCollaboratorChange(Ticket ticket, SysUser worker, boolean added) {
        String name = Texts.nullToEmpty(worker.getRealName());
        notificationService.send(ticket.getTenantId(), worker.getId(),
                added ? "TICKET_COLLABORATOR_ADDED" : "TICKET_COLLABORATOR_REMOVED",
                added ? "你被加入协作" : "你已不是协作人",
                "工单 " + ticket.getTicketNo() + "（" + location(ticket) + "）"
                        + (added ? "：请与主责师傅一起处理" : "：已改由他人处理"),
                ticket.getId());

        Long ownerId = ticket.getWorkerId();
        if (ownerId != null && !ownerId.equals(worker.getId())) {
            notificationService.send(ticket.getTenantId(), ownerId,
                    added ? "TICKET_COLLABORATOR_ADDED" : "TICKET_COLLABORATOR_REMOVED",
                    added ? "有维修工加入协作" : "协作者已移除",
                    "工单 " + ticket.getTicketNo()
                            + (added ? "：已请 " + name + " 一起处理" : "：已移除协作者 " + name),
                    ticket.getId());
        }
    }
    void notifySplit(Ticket source, Ticket created) {
        notificationService.send(source.getTenantId(), source.getStudentId(), "TICKET_SPLIT",
                "报修已拆成两张单",
                "原工单 " + source.getTicketNo() + " 里的问题已拆成两张单分别处理，新工单：" + created.getTicketNo(),
                source.getId());
        if (source.getWorkerId() != null) {
            notificationService.send(source.getTenantId(), source.getWorkerId(), "TICKET_SPLIT",
                    "你的工单已拆出一部分",
                    "工单 " + source.getTicketNo() + " 已拆出新工单 " + created.getTicketNo()
                            + "，那一部分由另一个人处理",
                    source.getId());
        }
    }
    String location(Ticket ticket) {
        String building = readModelAssembler.buildingNames(List.of(ticket.getBuildingId())).get(ticket.getBuildingId());
        return (building == null ? "" : building) + ticket.getRoom();
    }
}
