package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.common.WorkerTaskScope;
import com.bluemalic.repair.vo.TicketCollaboratorVO;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.TicketCollaborator;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketCollaboratorMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.NotificationService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Consumer;

/**
 * 工单域的共享件：条件更新、流转日志、状态变更通知，外加四个域共用的校验、批量取名与工单号生成。
 *
 * <p>此前它们是 {@link TicketServiceImpl} 的私有方法，而状态流转本身横跨
 * 学生端 / 维修工端 / 后勤端三段——拆分的第一步先把这三件抽成包私有组件，
 * 让后面的按域拆分（Query / Assignment / Timeout / Flow）注入复用，
 * <b>而不是把"条件更新 + 写日志 + 发通知"的口径复制成多份</b>。
 *
 * <p>只做纯移动：方法体一字不改，行为等价由全量集成测试保证（全部走 HTTP 层，
 * 接口签名不变则测试无需改动）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TicketTransitionSupport {

    private final TicketMapper ticketMapper;
    private final BuildingMapper buildingMapper;
    private final RepairCodeMapper repairCodeMapper;
    private final SysUserMapper sysUserMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final TicketCollaboratorMapper ticketCollaboratorMapper;
    private final WorkerBuildingMapper workerBuildingMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final DateTimeFormatter TICKET_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
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
    /**
     * 派单 / 转派共用的目标校验：必须是**本租户**、启用中的维修工。
     *
     * <p>跨租户的师傅与"不存在的师傅"返回同一个错误：不告诉调用方"这个师傅是别家的"。
     */
    SysUser requireEnabledWorker(Long workerId, Long tenantId) {
        SysUser worker = sysUserMapper.selectById(workerId);
        if (worker == null || !Integer.valueOf(UserType.WORKER.getCode()).equals(worker.getUserType())
                || !Integer.valueOf(1).equals(worker.getStatus())
                || !tenantId.equals(worker.getTenantId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "维修工不存在或已停用");
        }
        return worker;
    }

    /**
     * 跨楼栋强制派单的留痕文案；师傅负责这栋楼时返回 {@code null}（正常的派单不写多余备注）。
     *
     * <p>它进 `ticket_log.remark`，所以在管理端的工单时间线上看得见"这一单是被谁强行跨楼栋派下来的"。
     */
    String crossBuildingTrace(Long tenantId, long buildingId, long workerId) {
        boolean covered = workerBuildingMapper.selectCount(Wrappers.<WorkerBuilding>lambdaQuery()
                .eq(WorkerBuilding::getTenantId, tenantId)
                .eq(WorkerBuilding::getWorkerId, workerId)
                .eq(WorkerBuilding::getBuildingId, buildingId)) > 0;
        if (covered) {
            return null;
        }
        log.warn("跨楼栋强制派单 buildingId={} workerId={}（该师傅不负责这栋楼）", buildingId, workerId);
        return "跨楼栋强制派单（该师傅不负责本单楼栋）";
    }
    boolean notifiedBefore(Long tenantId, long ticketId, TicketAction action) {
        return ticketLogMapper.selectCount(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTenantId, tenantId)
                .eq(TicketLog::getTicketId, ticketId)
                .eq(TicketLog::getAction, action.name())) > 0;
    }

    // ==================== 私有工具 ====================

    boolean isNotAssignee(Ticket ticket, long workerId) {
        return ticket.getWorkerId() == null || ticket.getWorkerId() != workerId;
    }

    /**
     * 是不是这单的**参与人**：主责 或 协作者。协作者能到场、能完工；接单与驳回仍只给主责
     * ——那两件事是"我认领这单"和"这单不该我做"，属于处置权（`docs/01` §4.5）。
     */
    boolean isNotParticipant(Ticket ticket, long workerId) {
        return isNotAssignee(ticket, workerId) && !isCollaborator(ticket, workerId);
    }

    /** 这个人在不在这单的协作者名单里。带 tenant_id 条件——防御纵深，见 {@link #collaboratedTicketIds}。 */
    boolean isCollaborator(Ticket ticket, long workerId) {
        return ticketCollaboratorMapper.selectCount(Wrappers.<TicketCollaborator>lambdaQuery()
                .eq(TicketCollaborator::getTenantId, ticket.getTenantId())
                .eq(TicketCollaborator::getTicketId, ticket.getId())
                .eq(TicketCollaborator::getWorkerId, workerId)) > 0;
    }

    long collaboratorCount(Ticket ticket) {
        return ticketCollaboratorMapper.selectCount(Wrappers.<TicketCollaborator>lambdaQuery()
                .eq(TicketCollaborator::getTenantId, ticket.getTenantId())
                .eq(TicketCollaborator::getTicketId, ticket.getId()));
    }

    /**
            return List.of();
        }
        Map<Long, String> names = sysUserMapper.selectByIds(
                        rows.stream().map(TicketCollaborator::getWorkerId).distinct().toList()).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> nullToEmpty(u.getRealName())));
        return rows.stream().map(row -> {
            TicketCollaboratorVO vo = new TicketCollaboratorVO();
            vo.setWorkerId(row.getWorkerId());
            vo.setWorkerName(names.get(row.getWorkerId()));
            return vo;
        }).toList();
    }

    /**
     * 本页里"我参与协作"的工单 ID（给列表打「协作」标记用）。一次查询，不逐条回库。
     *
     * <p>条件里只带 worker_id 不带 tenant_id，与上面查 `worker_building` 同一个理由：
     * worker_id 是全局唯一的雪花 ID（不可能命中别家租户的人），而工单 ID 取自**已经过租户过滤**的一页。
     */
    Set<Long> collaboratedTicketIds(Long tenantId, long workerId, List<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Set.of();
        }
        return ticketCollaboratorMapper.selectList(Wrappers.<TicketCollaborator>lambdaQuery()
                        .eq(TicketCollaborator::getTenantId, tenantId)
                        .eq(TicketCollaborator::getWorkerId, workerId)
                        .in(TicketCollaborator::getTicketId, ticketIds))
                .stream().map(TicketCollaborator::getTicketId).collect(Collectors.toSet());
    }

    Ticket requireTicket(long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            // 查不到 = 不存在，或不在当前用户的数据范围内——对外统一说"不存在"，不泄露差别
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    SysUser requireUser(long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
        return user;
    }

    RepairCode requireRepairCode(String code, Long tenantId) {
        RepairCode repairCode = repairCodeMapper.selectOne(Wrappers.<RepairCode>lambdaQuery()
                .eq(RepairCode::getTenantId, tenantId)
                .eq(RepairCode::getCode, code)
                .eq(RepairCode::getStatus, 1));
        if (repairCode == null) {
            throw new BizException(ErrorCode.REPAIR_CODE_INVALID);
        }
        return repairCode;
    }

    /**
     * 楼栋必须是本租户下、启用中的。校验对象是"最终落到工单上的那个 buildingId"，所以两条提交路径都要过。
     *
     * <p><b>直接传 buildingId 不校验会怎样</b>：能建出"后勤看得到、师傅永远看不到"的孤儿工单——
     * 工单的可见范围是"师傅负责的楼栋"，而一个不存在 / 别家租户的楼栋不在任何人的范围内，
     * 这张单从此没人能接。
     *
     * <p><b>扫码路径不校验会怎样</b>：停用一个楼栋并不会自动停用挂在它下面的报修码，
     * 门上的码照扫、单照建，"停用楼栋 = 不能再用于新报修"（docs/03 §5.4）就成了空话。
     *
     * <p>对外不区分"不存在 / 别家租户的 / 已停用"，与类别的校验口径一致（也就不会透露别家的楼栋存在）。
     */
    void requireEnabledBuilding(Long buildingId, Long tenantId) {
        Building building = buildingMapper.selectById(buildingId);
        if (building == null || !building.getTenantId().equals(tenantId)
                || !Integer.valueOf(1).equals(building.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "楼栋不存在或已停用");
        }
    }

    /**
     * 类别必须是本租户下、启用中的。提交报修与拆单共用——**同一条校验只能有一份实现**，
     * 否则两条路径会各自漂移（拆单能挑到已停用的类别，就是"少写一次校验"的典型后果）。
     *
     * <p>对外不区分"不存在 / 别家租户的 / 已停用"，与楼栋的校验口径一致。
     */
    TicketCategory requireEnabledCategory(Long categoryId, Long tenantId) {
        TicketCategory category = ticketCategoryMapper.selectById(categoryId);
        if (category == null || !category.getTenantId().equals(tenantId)
                || !Integer.valueOf(1).equals(category.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报修类别不存在或已停用");
        }
        return category;
    }

    /** 工单号：WX + 日期 + 当日序号（Redis INCR），uk_ticket_no 兜底唯一。 */
    String nextTicketNo() {
        String date = LocalDate.now().format(TICKET_NO_DATE);
        String seqKey = "ticket:no:seq:" + date;
        Long seq = stringRedisTemplate.opsForValue().increment(seqKey);
        stringRedisTemplate.expire(seqKey, Duration.ofDays(2));
        return "WX" + date + String.format("%06d", seq);
    }

    /** 批量 id → 楼栋名（selectByIds 一次拿全，避免循环回库的 N+1）。 */
    Map<Long, String> buildingNames(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return buildingMapper.selectByIds(distinct).stream()
                .collect(Collectors.toMap(Building::getId, Building::getName));
    }

    Map<Long, String> categoryNames(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return ticketCategoryMapper.selectByIds(distinct).stream()
                .collect(Collectors.toMap(TicketCategory::getId, TicketCategory::getName));
    }

    String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
    /** 详情里的协作者名单：一次查询 + 一次批量取姓名，不做 N+1。 */
    List<TicketCollaboratorVO> collaboratorsOf(Ticket ticket) {
        List<TicketCollaborator> rows = ticketCollaboratorMapper.selectList(
                Wrappers.<TicketCollaborator>lambdaQuery()
                        .eq(TicketCollaborator::getTenantId, ticket.getTenantId())
                        .eq(TicketCollaborator::getTicketId, ticket.getId())
                        .orderByAsc(TicketCollaborator::getCreateTime));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = sysUserMapper.selectByIds(
                        rows.stream().map(TicketCollaborator::getWorkerId).distinct().toList()).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> nullToEmpty(u.getRealName())));
        return rows.stream().map(row -> {
            TicketCollaboratorVO vo = new TicketCollaboratorVO();
            vo.setWorkerId(row.getWorkerId());
            vo.setWorkerName(names.get(row.getWorkerId()));
            return vo;
        }).toList();
    }
}
