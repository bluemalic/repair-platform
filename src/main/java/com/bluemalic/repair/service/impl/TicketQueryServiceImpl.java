package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.Paging;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.common.WorkerTaskScope;
import com.bluemalic.repair.converter.TicketConverter;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.TicketQueryService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.RepairCodeVO;
import com.bluemalic.repair.vo.TicketDetailVO;
import com.bluemalic.repair.vo.TicketLogVO;
import com.bluemalic.repair.vo.TicketVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单查询域实现：报修码定位、学生列表、维修工任务、详情。从 TicketServiceImpl 拆出（纯移动）。
 *
 * <p>可见范围由数据权限拦截器注入（ADR-002），这里只写"这个视图要看什么"的业务筛选；
 * 校验与批量取名复用 {@link TicketTransitionSupport}，与流转域共用同一份口径。
 */
@Service
@RequiredArgsConstructor
public class TicketQueryServiceImpl implements TicketQueryService {

    private final TicketMapper ticketMapper;
    private final WorkerBuildingMapper workerBuildingMapper;
    private final TicketLogMapper ticketLogMapper;
    private final TicketEvaluationMapper ticketEvaluationMapper;
    private final SysUserMapper sysUserMapper;
    private final BuildingMapper buildingMapper;
    private final TicketTransitionSupport transitions;
    private final CurrentTenantService currentTenantService;

    /** 维修工「我的任务」默认要显示的进行中状态（待接单 / 处理中 / 待验收）。 */
    private static final List<Integer> ACTIVE_STATUSES = List.of(
            TicketStatus.TO_ACCEPT.getCode(), TicketStatus.PROCESSING.getCode(), TicketStatus.TO_VERIFY.getCode());

    @Override
    public RepairCodeVO byCode(String code) {
        long userId = StpUtil.getLoginIdAsLong();
        SysUser user = transitions.requireUser(userId);
        RepairCode repairCode = transitions.requireRepairCode(code, user.getTenantId());
        Building building = buildingMapper.selectById(repairCode.getBuildingId());

        RepairCodeVO vo = new RepairCodeVO();
        vo.setBuildingId(repairCode.getBuildingId());
        vo.setBuildingName(building == null ? null : building.getName());
        vo.setRoom(repairCode.getRoom());
        return vo;
    }

    @Override
    public PageResult<TicketVO> page(long pageNum, long pageSize, Integer status, Long buildingId, Long categoryId) {
        // 数据范围（学生=本人 / 维修工=负责楼栋 / 后勤=全部）由数据权限拦截器注入，这里只管筛选与排序
        Page<Ticket> page = ticketMapper.selectPage(
                new Page<>(Paging.clamp(pageNum), Paging.clamp(pageSize)),
                Wrappers.<Ticket>lambdaQuery()
                        .eq(status != null, Ticket::getStatus, status)
                        .eq(buildingId != null, Ticket::getBuildingId, buildingId)
                        .eq(categoryId != null, Ticket::getCategoryId, categoryId)
                        .orderByDesc(Ticket::getSubmitTime));

        Map<Long, String> buildings = transitions.buildingNames(page.getRecords().stream().map(Ticket::getBuildingId).toList());
        Map<Long, String> categories = transitions.categoryNames(page.getRecords().stream().map(Ticket::getCategoryId).toList());

        Page<TicketVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
                .map(t -> TicketConverter.toVO(t, buildings, categories)).toList());
        return PageResult.of(voPage);
    }

    @Override
    public PageResult<TicketVO> pageWorkerTasks(long pageNum, long pageSize, Integer status, WorkerTaskScope scope) {
        long workerId = StpUtil.getLoginIdAsLong();

        // 「本楼栋」要先知道"我的楼栋";一个都不负责时这个视图就是空的，**不必查库**
        // （也不能靠 `in(空集合)`——那会退化成"不加条件"，反而把全部单放出来）
        List<Long> myBuildings = List.of();
        if (scope == WorkerTaskScope.BUILDING) {
            myBuildings = workerBuildingMapper.selectList(
                            Wrappers.<WorkerBuilding>lambdaQuery()
                                    .eq(WorkerBuilding::getTenantId, currentTenantService.requireTenantId())
                                    .eq(WorkerBuilding::getWorkerId, workerId))
                    .stream().map(WorkerBuilding::getBuildingId).distinct().toList();
            if (myBuildings.isEmpty()) {
                return PageResult.of(new Page<>(Paging.clamp(pageNum), Paging.clamp(pageSize), 0));
            }
        }

        // 可见范围仍由拦截器注入（负责楼栋 或 派给我的 或 我协作的）；下面的条件是**这个视图要看什么**，
        // 属于业务筛选不属于权限：mine → 派给我的 + 我协作的，building → 我负责的楼栋
        LambdaQueryWrapper<Ticket> query = Wrappers.<Ticket>lambdaQuery()
                .in(scope == WorkerTaskScope.BUILDING, Ticket::getBuildingId, myBuildings)
                // 紧急度高的置顶；同一档内先来的在前（先提交/先派单的先做）——docs/01 §4.2
                .orderByDesc(Ticket::getUrgency)
                .orderByAsc(Ticket::getSubmitTime);
        if (scope == WorkerTaskScope.MINE) {
            // 「我的任务」= 派给我的 **+ 我参与协作的**（docs/01 §4.2）。协作单必须一起看得到——
            // 漏看的代价是"这单没人去修"。这里用 EXISTS 而不是查列表再 IN：协作者数量没有上界
            // （历史协作会一直累积），而 idx_worker(worker_id, ticket_id) 让这条子查询走索引。
            query.and(w -> w.eq(Ticket::getWorkerId, workerId)
                    .or().exists("SELECT 1 FROM ticket_collaborator c"
                            + " WHERE c.ticket_id = ticket.id AND c.worker_id = {0}", workerId));
        }
        if (status != null) {
            query.eq(Ticket::getStatus, status);
        } else if (scope == WorkerTaskScope.MINE) {
            // 「我的任务」默认只给进行中：待接单 / 处理中 / 待验收。
            // 终态（已完成/已关闭/已驳回/已撤单）要看就显式传 status，或者去「本楼栋」视图看
            query.in(Ticket::getStatus, ACTIVE_STATUSES);
        }

        Page<Ticket> page = ticketMapper.selectPage(new Page<>(Paging.clamp(pageNum), Paging.clamp(pageSize)), query);

        Map<Long, String> buildings = transitions.buildingNames(page.getRecords().stream().map(Ticket::getBuildingId).toList());
        Map<Long, String> categories = transitions.categoryNames(page.getRecords().stream().map(Ticket::getCategoryId).toList());
        Set<Long> collaborated = transitions.collaboratedTicketIds(currentTenantService.requireTenantId(), workerId,
                page.getRecords().stream().map(Ticket::getId).toList());

        Page<TicketVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
                .map(t -> {
                    TicketVO vo = TicketConverter.toVO(t, buildings, categories);
                    // 这个列表里**始终填**布尔值（其它列表才留 null）：前端按它打「协作」标记
                    vo.setCollaborative(collaborated.contains(t.getId()));
                    return vo;
                }).toList());
        return PageResult.of(voPage);
    }

    @Override
    public TicketDetailVO detail(long id) {
        Ticket ticket = transitions.requireTicket(id);
        Map<Long, String> buildings = transitions.buildingNames(List.of(ticket.getBuildingId()));
        Map<Long, String> categories = transitions.categoryNames(List.of(ticket.getCategoryId()));

        List<TicketLog> logs = ticketLogMapper.selectList(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTenantId, ticket.getTenantId())
                .eq(TicketLog::getTicketId, id).orderByAsc(TicketLog::getCreateTime));
        Map<Long, String> operators = sysUserMapper.selectByIds(
                        logs.stream().map(TicketLog::getOperatorId).distinct().toList()).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> transitions.nullToEmpty(u.getRealName())));
        List<TicketLogVO> logVOs = logs.stream()
                .map(l -> TicketConverter.toLogVO(l, operators)).toList();

        TicketEvaluation evaluation = ticketEvaluationMapper.selectOne(
                Wrappers.<TicketEvaluation>lambdaQuery()
                        .eq(TicketEvaluation::getTenantId, ticket.getTenantId())
                        .eq(TicketEvaluation::getTicketId, id));

        // 拆单来源：按 id 取父单的工单号（走主键）。**取不到就留空**——父单可能不在当前用户的
        // 可见范围里（例如协作者看得到子单、看不到父单），那是正常的，不该因此报错或泄露
        String parentTicketNo = null;
        if (ticket.getParentTicketId() != null) {
            Ticket parent = ticketMapper.selectById(ticket.getParentTicketId());
            parentTicketNo = parent == null ? null : parent.getTicketNo();
        }

        TicketDetailVO vo = TicketConverter.toDetailVO(ticket, buildings, categories,
                transitions.collaboratorsOf(ticket), parentTicketNo, logVOs, evaluation);
        // 主责的姓名在这里补：转换器已经有 7 个参数，再加一个不如就近补一行。
        // 详情页要回答"谁负责"——只给一个 ID，看的人还得自己去别处查
        if (ticket.getWorkerId() != null) {
            SysUser worker = sysUserMapper.selectById(ticket.getWorkerId());
            vo.setWorkerName(worker == null ? null : transitions.nullToEmpty(worker.getRealName()));
        }
        return vo;
    }
}
