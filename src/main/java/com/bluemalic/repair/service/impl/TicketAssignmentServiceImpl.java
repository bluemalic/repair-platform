package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.converter.TicketConverter;
import com.bluemalic.repair.dto.TicketCollaboratorDTO;
import com.bluemalic.repair.dto.TicketDispatchDTO;
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
 * <p>五类动作都由后勤触发；跨楼栋强制派单的留痕、目标校验与流转台账复用
 * {@link TicketTransitionSupport}，超时节点登记走 {@code TimeoutService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketAssignmentServiceImpl implements TicketAssignmentService {

    private final TicketMapper ticketMapper;
    private final TicketCollaboratorMapper ticketCollaboratorMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
    private final TimeoutService timeoutService;
    private final TicketTransitionSupport transitions;

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
        Ticket ticket = transitions.requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.TO_ACCEPT.getCode());

        SysUser worker = transitions.requireEnabledWorker(dto.getWorkerId(), ticket.getTenantId());
        // 已有师傅的单不能走"派单"：那是**换人**，要走转派——它要重置计时、清掉上一轮的
        // 接单/到场时间、并单独留一条 TRANSFER 台账。两条路都能换人的话，台账就分不清了
        if (ticket.getWorkerId() != null) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED, "该工单已有维修工，换人请用转派");
        }

        // 跨楼栋派单 = 紧急抽调（docs/01 §4.2）：**放行**，但要留痕——为什么允许见 §4.2，
        // 为的是不让"派错楼栋"变成一张没人能操作的单（被派的人按『派给我的单』看得到、能处理）
        String crossBuilding = transitions.crossBuildingTrace(ticket.getTenantId(), ticket.getBuildingId(), worker.getId());

        transitions.conditionalUpdate(id, TicketStatus.TO_ACCEPT.getCode(), TicketAction.DISPATCH, adminId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> {
                    entity.setWorkerId(dto.getWorkerId());
                    entity.setDispatchType(1);
                    entity.setDispatchTime(LocalDateTime.now());
                },
                crossBuilding);
        transitions.notifyTransition(ticket, TicketAction.DISPATCH, dto.getWorkerId(), null);
        // 登记未接单提醒：到期仍无人接单就提醒调度方（M3）；重新派单会覆盖到期时间
        timeoutService.registerAccept(id);
        log.info("派单 ticketId={} workerId={} operator={} {}", id, dto.getWorkerId(), adminId,
                crossBuilding == null ? "" : crossBuilding);
    }
    public void transfer(long id, TicketTransferDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        // 20 → 20 / 30 → 20 都是允许的（docs/02 §5）；40 及之后不允许——那时该走打回或驳回
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.TO_ACCEPT.getCode());

        SysUser worker = transitions.requireEnabledWorker(dto.getWorkerId(), ticket.getTenantId());
        if (worker.getId().equals(ticket.getWorkerId())) {
            // 转给同一个人的唯一效果是把 dispatch_time 往后推——那是一条绕过"24h 未接单提醒"的路
            throw new BizException(ErrorCode.PARAM_INVALID, "新维修工与当前维修工相同，不需要转派");
        }
        Long previousWorkerId = ticket.getWorkerId();
        String crossBuilding = transitions.crossBuildingTrace(ticket.getTenantId(), ticket.getBuildingId(), worker.getId());
        String remark = "转派给 " + worker.getRealName()
                + (crossBuilding == null ? "" : "；" + crossBuilding)
                + "；原因：" + dto.getReason();

        transitions.conditionalUpdate(id, TicketStatus.TO_ACCEPT.getCode(), TicketAction.TRANSFER, adminId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus())
                        // 上一轮的接单/到场时间必须清掉：响应时长口径是"到场 − 派单"，
                        // 留着旧值会算出负数或虚高的数。谁来过在 ticket_log 里，不丢
                        .set(Ticket::getAcceptTime, null)
                        .set(Ticket::getArriveTime, null),
                entity -> {
                    entity.setWorkerId(worker.getId());
                    entity.setDispatchType(1);
                    // 计时从头开始：不能让新师傅背前一个人的延迟
                    entity.setDispatchTime(LocalDateTime.now());
                },
                remark);
        // 通知原师傅（他的活没了，得知道为什么）：NoticeSpec(toStudent=false) 取的就是 ticket.workerId，
        // 而这时的 ticket 还是转派前的对象
        transitions.notifyTransition(ticket, TicketAction.TRANSFER, null,
                "已转给 " + worker.getRealName() + "；原因：" + dto.getReason());
        // 通知新师傅：与派单同一条文案
        transitions.notifyTransition(ticket, TicketAction.DISPATCH, worker.getId(), null);
        // 计时重来，超时节点也要重登记（先撤掉旧节点的登记，避免按旧时间触发）
        timeoutService.cancel(id);
        timeoutService.registerAccept(id);
        log.info("转派 ticketId={} from={} to={} operator={} reason={}",
                id, previousWorkerId, worker.getId(), adminId, dto.getReason());
    }
    public void addCollaborator(long id, TicketCollaboratorDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        requireCollaboratingStatus(ticket, "加协作者");
        // 与派单同一条校验：本租户、启用中的维修工（跨租户与"不存在"对外是同一个错误）
        SysUser worker = transitions.requireEnabledWorker(dto.getWorkerId(), ticket.getTenantId());
        if (worker.getId().equals(ticket.getWorkerId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "他就是这单的主责师傅，不用再加成协作者");
        }
        if (transitions.isCollaborator(ticket, worker.getId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该师傅已经是这单的协作者");
        }
        if (transitions.collaboratorCount(ticket) >= MAX_COLLABORATORS) {
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
                TicketAction.ADD_COLLABORATOR, adminId, "协作者：" + transitions.nullToEmpty(worker.getRealName()));
        notifyCollaboratorChange(ticket, worker, true);
        log.info("加协作者 ticketId={} workerId={} operator={}", id, worker.getId(), adminId);
    }
    public void removeCollaborator(long id, long workerId) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
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
                "移除协作者：" + (worker == null ? String.valueOf(workerId) : transitions.nullToEmpty(worker.getRealName())));
        if (worker != null) {
            // 通知当事人：他刚失去这张单的可见范围，不告诉他，他会照旧去现场
            notifyCollaboratorChange(ticket, worker, false);
        }
        log.info("移除协作者 ticketId={} workerId={} operator={}", id, workerId, adminId);
    }
    public TicketVO split(long id, TicketSplitDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = transitions.requireTicket(id);
        if (ticket.getParentTicketId() != null) {
            // 只拆一层：拆出来的单再拆下去会变成一棵谁都说不清的树（docs/01 §4.5）
            throw new BizException(ErrorCode.PARAM_INVALID, "拆出来的工单不能再拆");
        }
        if (!SPLITTABLE_STATUSES.contains(ticket.getStatus())) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    TicketStatus.of(ticket.getStatus()).getDesc() + "的工单不能拆单");
        }
        Long categoryId = dto.getCategoryId() == null ? ticket.getCategoryId() : dto.getCategoryId();
        transitions.requireEnabledCategory(categoryId, ticket.getTenantId());

        Ticket created = new Ticket();
        created.setTenantId(ticket.getTenantId());
        created.setTicketNo(transitions.nextTicketNo());
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

        return TicketConverter.toVO(created, transitions.buildingNames(List.of(created.getBuildingId())),
                transitions.categoryNames(List.of(created.getCategoryId())));
    }
    void requireCollaboratingStatus(Ticket ticket, String action) {
        int status = ticket.getStatus();
        if (status != TicketStatus.TO_ACCEPT.getCode() && status != TicketStatus.PROCESSING.getCode()) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    TicketStatus.of(status).getDesc() + "的工单不能" + action);
        }
    }
    void notifyCollaboratorChange(Ticket ticket, SysUser worker, boolean added) {
        String name = transitions.nullToEmpty(worker.getRealName());
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
        String building = transitions.buildingNames(List.of(ticket.getBuildingId())).get(ticket.getBuildingId());
        return (building == null ? "" : building) + ticket.getRoom();
    }
}
