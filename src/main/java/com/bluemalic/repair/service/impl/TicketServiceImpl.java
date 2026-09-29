package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.Paging;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.common.WorkerTaskScope;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.converter.TicketConverter;
import com.bluemalic.repair.dto.TicketArriveDTO;
import com.bluemalic.repair.dto.TicketCollaboratorDTO;
import com.bluemalic.repair.dto.TicketCreateDTO;
import com.bluemalic.repair.dto.TicketDispatchDTO;
import com.bluemalic.repair.dto.TicketEvaluateDTO;
import com.bluemalic.repair.dto.TicketFinishDTO;
import com.bluemalic.repair.dto.TicketRejectDTO;
import com.bluemalic.repair.dto.TicketReworkDTO;
import com.bluemalic.repair.dto.TicketSplitDTO;
import com.bluemalic.repair.dto.TicketTransferDTO;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.TicketCollaborator;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketCollaboratorMapper;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.TicketService;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.RepairCodeVO;
import com.bluemalic.repair.vo.TicketCollaboratorVO;
import com.bluemalic.repair.vo.TicketDetailVO;
import com.bluemalic.repair.vo.TicketLogVO;
import com.bluemalic.repair.vo.TicketVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单业务实现。
 *
 * <p>三条铁律贯穿所有方法：
 * <ol>
 *   <li>状态流转先过 {@link TicketStatus#checkTransition}，再用<b>条件更新</b>落库
 *       （update ... where status = 旧状态），受影响行数 0 即视为被并发改动——
 *       幂等与并发安全都靠它，不靠"先查再改"</li>
 *   <li>每次流转写一条 ticket_log（from / to / action / operator），可追溯</li>
 *   <li>查询与更新都不写数据范围条件——学生 / 维修工的可见范围由数据权限拦截器注入（ADR-002）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private static final DateTimeFormatter TICKET_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** ticket_log.operator_id 用 0 表示"系统"（超时调度等无登录态动作），与真人操作区分。 */
    private static final long SYSTEM_OPERATOR = 0L;

    /**
     * 维修工「我的任务」默认要显示的进行中状态（待接单 / 处理中 / 待验收）。
     * 终态不进默认视图——工作台回答"我现在该干什么"，历史去「本楼栋」或显式筛状态看（docs/01 §4.2）。
     */
    private static final List<Integer> ACTIVE_STATUSES = List.of(
            TicketStatus.TO_ACCEPT.getCode(), TicketStatus.PROCESSING.getCode(), TicketStatus.TO_VERIFY.getCode());

    /** 超时提醒类通知（不是状态跃迁，所以不进 TRANSITION_NOTICE 表）：类型与标题放一处。 */
    private static final String NOTICE_ACCEPT_TIMEOUT = "TICKET_ACCEPT_TIMEOUT";
    private static final String TITLE_ACCEPT_TIMEOUT = "工单超时未接单";
    private static final String NOTICE_PROCESS_TIMEOUT = "TICKET_PROCESS_TIMEOUT";
    private static final String TITLE_PROCESS_TIMEOUT = "工单处理超时升级";

    /**
     * 一单最多几个协作者（`docs/01` §4.5）。**再多就不叫"搭把手"了**——那种情况该看是不是该拆单。
     * 这个上限不是并发的硬保证（两个请求同时加可能都通过检查），但唯一索引挡住了重复，
     * 最多多出一个人；为它加锁不值得，写在这里免得下次被当成 bug 查。
     */
    private static final int MAX_COLLABORATORS = 3;

    /** 能拆单的状态：活还没干完才谈得上"拆"（40 之后已经修完了，见 `docs/01` §4.5）。 */
    private static final List<Integer> SPLITTABLE_STATUSES = List.of(
            TicketStatus.TO_DISPATCH.getCode(), TicketStatus.TO_ACCEPT.getCode(), TicketStatus.PROCESSING.getCode());

    private final TicketMapper ticketMapper;
    private final TicketLogMapper ticketLogMapper;
    private final TicketEvaluationMapper ticketEvaluationMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final TicketCollaboratorMapper ticketCollaboratorMapper;
    private final BuildingMapper buildingMapper;
    private final RepairCodeMapper repairCodeMapper;
    private final SysUserMapper sysUserMapper;
    private final WorkerBuildingMapper workerBuildingMapper;
    private final NotificationService notificationService;
    private final CurrentTenantService currentTenantService;
    /** 流转三件套（条件更新 / 写日志 / 发通知）抽到包私有组件，四个域拆分后共用这一份口径。 */
    private final TicketTransitionSupport transitions;
    private final TimeoutService timeoutService;
    private final TimeoutRule timeoutRule;
    private final StringRedisTemplate stringRedisTemplate;

    // ==================== 学生端 ====================

    @Override
    @Transactional
    public TicketVO submit(TicketCreateDTO dto) {
        long studentId = StpUtil.getLoginIdAsLong();
        SysUser student = requireUser(studentId);

        Long buildingId;
        String room;
        if (dto.getRepairCode() != null && !dto.getRepairCode().isBlank()) {
            RepairCode repairCode = requireRepairCode(dto.getRepairCode(), student.getTenantId());
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
        requireEnabledBuilding(buildingId, student.getTenantId());

        TicketCategory category = requireEnabledCategory(dto.getCategoryId(), student.getTenantId());

        Ticket ticket = new Ticket();
        ticket.setTenantId(student.getTenantId());
        ticket.setTicketNo(nextTicketNo());
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
                buildingNames(List.of(buildingId)), categoryNames(List.of(dto.getCategoryId())));
    }

    @Override
    public RepairCodeVO byCode(String code) {
        long userId = StpUtil.getLoginIdAsLong();
        SysUser user = requireUser(userId);
        RepairCode repairCode = requireRepairCode(code, user.getTenantId());
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

        Map<Long, String> buildings = buildingNames(page.getRecords().stream().map(Ticket::getBuildingId).toList());
        Map<Long, String> categories = categoryNames(page.getRecords().stream().map(Ticket::getCategoryId).toList());

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

        Map<Long, String> buildings = buildingNames(page.getRecords().stream().map(Ticket::getBuildingId).toList());
        Map<Long, String> categories = categoryNames(page.getRecords().stream().map(Ticket::getCategoryId).toList());
        Set<Long> collaborated = collaboratedTicketIds(currentTenantService.requireTenantId(), workerId,
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
        Ticket ticket = requireTicket(id);
        Map<Long, String> buildings = buildingNames(List.of(ticket.getBuildingId()));
        Map<Long, String> categories = categoryNames(List.of(ticket.getCategoryId()));

        List<TicketLog> logs = ticketLogMapper.selectList(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTenantId, ticket.getTenantId())
                .eq(TicketLog::getTicketId, id).orderByAsc(TicketLog::getCreateTime));
        Map<Long, String> operators = sysUserMapper.selectByIds(
                        logs.stream().map(TicketLog::getOperatorId).distinct().toList()).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> nullToEmpty(u.getRealName())));
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
                collaboratorsOf(ticket), parentTicketNo, logVOs, evaluation);
        // 主责的姓名在这里补：转换器已经有 7 个参数，再加一个不如就近补一行。
        // 详情页要回答"谁负责"——只给一个 ID，看的人还得自己去别处查
        if (ticket.getWorkerId() != null) {
            SysUser worker = sysUserMapper.selectById(ticket.getWorkerId());
            vo.setWorkerName(worker == null ? null : nullToEmpty(worker.getRealName()));
        }
        return vo;
    }

    @Override
    @Transactional
    public void cancel(long id) {
        long studentId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.CANCELED.getCode());

        // 学生只能撤自己的单——数据范围由拦截器注入到 UPDATE 里，这里不再手写 student_id 条件
        transitions.conditionalUpdate(id, TicketStatus.CANCELED.getCode(), TicketAction.CANCEL, studentId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> entity.setCloseTime(LocalDateTime.now()));
    }

    @Override
    @Transactional
    public void rework(long id, TicketReworkDTO dto) {
        long studentId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
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

    @Override
    @Transactional
    public void evaluate(long id, TicketEvaluateDTO dto) {
        long studentId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
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

    // ==================== 维修工端 ====================

    @Override
    @Transactional
    public void accept(long id) {
        long workerId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.PROCESSING.getCode());
        if (isNotAssignee(ticket, workerId)) {
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

    @Override
    @Transactional
    public void arrive(long id, TicketArriveDTO dto) {
        long workerId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        if (ticket.getStatus() != TicketStatus.PROCESSING.getCode()) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED, "工单不在处理中，无法到场打卡");
        }
        // 到场/完工对**参与人**开放：主责或协作者（docs/01 §4.5）。
        // 接单与驳回仍只给主责——那两件事是"我认领这单"和"这单不该我做"，属于处置权
        if (isNotParticipant(ticket, workerId)) {
            throw new BizException(ErrorCode.TICKET_ALREADY_ACCEPTED);
        }
        // 扫码到场的关键校验：码对应的位置必须和工单一致，防止"人没到先打卡"
        RepairCode repairCode = requireRepairCode(dto.getRepairCode(), ticket.getTenantId());
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

    @Override
    @Transactional
    public void finish(long id, TicketFinishDTO dto) {
        long workerId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.TO_VERIFY.getCode());
        if (isNotParticipant(ticket, workerId)) {
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
                    "工单 " + ticket.getTicketNo() + " 已由 " + nullToEmpty(requireUser(workerId).getRealName())
                            + " 完工，等待学生验收",
                    ticket.getId());
        }
    }

    @Override
    @Transactional
    public void rejectByWorker(long id, TicketRejectDTO dto) {
        long workerId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.REJECTED.getCode());
        if (isNotAssignee(ticket, workerId)) {
            throw new BizException(ErrorCode.TICKET_ALREADY_ACCEPTED);
        }
        doReject(ticket, workerId, dto.getReason());
        transitions.notifyTransition(ticket, TicketAction.REJECT, null, "被驳回：" + dto.getReason());
    }

    @Override
    @Transactional
    public void rejectByAdmin(long id, TicketRejectDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.REJECTED.getCode());
        doReject(ticket, adminId, dto.getReason());
        transitions.notifyTransition(ticket, TicketAction.REJECT, ticket.getStudentId(), "被驳回：" + dto.getReason());
        transitions.notifyTransition(ticket, TicketAction.REJECT, ticket.getWorkerId(), "被驳回：" + dto.getReason());
    }

    private void doReject(Ticket ticket, long operatorId, String reason) {
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

    // ==================== 后勤端 ====================

    @Override
    @Transactional
    public void dispatch(long id, TicketDispatchDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.TO_ACCEPT.getCode());

        SysUser worker = requireEnabledWorker(dto.getWorkerId(), ticket.getTenantId());
        // 已有师傅的单不能走"派单"：那是**换人**，要走转派——它要重置计时、清掉上一轮的
        // 接单/到场时间、并单独留一条 TRANSFER 台账。两条路都能换人的话，台账就分不清了
        if (ticket.getWorkerId() != null) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED, "该工单已有维修工，换人请用转派");
        }

        // 跨楼栋派单 = 紧急抽调（docs/01 §4.2）：**放行**，但要留痕——为什么允许见 §4.2，
        // 为的是不让"派错楼栋"变成一张没人能操作的单（被派的人按『派给我的单』看得到、能处理）
        String crossBuilding = crossBuildingTrace(ticket.getTenantId(), ticket.getBuildingId(), worker.getId());

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

    @Override
    @Transactional
    public void transfer(long id, TicketTransferDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        // 20 → 20 / 30 → 20 都是允许的（docs/02 §5）；40 及之后不允许——那时该走打回或驳回
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.TO_ACCEPT.getCode());

        SysUser worker = requireEnabledWorker(dto.getWorkerId(), ticket.getTenantId());
        if (worker.getId().equals(ticket.getWorkerId())) {
            // 转给同一个人的唯一效果是把 dispatch_time 往后推——那是一条绕过"24h 未接单提醒"的路
            throw new BizException(ErrorCode.PARAM_INVALID, "新维修工与当前维修工相同，不需要转派");
        }
        Long previousWorkerId = ticket.getWorkerId();
        String crossBuilding = crossBuildingTrace(ticket.getTenantId(), ticket.getBuildingId(), worker.getId());
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

    @Override
    @Transactional
    public void addCollaborator(long id, TicketCollaboratorDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        requireCollaboratingStatus(ticket, "加协作者");
        // 与派单同一条校验：本租户、启用中的维修工（跨租户与"不存在"对外是同一个错误）
        SysUser worker = requireEnabledWorker(dto.getWorkerId(), ticket.getTenantId());
        if (worker.getId().equals(ticket.getWorkerId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "他就是这单的主责师傅，不用再加成协作者");
        }
        if (isCollaborator(ticket, worker.getId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该师傅已经是这单的协作者");
        }
        if (collaboratorCount(ticket) >= MAX_COLLABORATORS) {
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
                TicketAction.ADD_COLLABORATOR, adminId, "协作者：" + nullToEmpty(worker.getRealName()));
        notifyCollaboratorChange(ticket, worker, true);
        log.info("加协作者 ticketId={} workerId={} operator={}", id, worker.getId(), adminId);
    }

    @Override
    @Transactional
    public void removeCollaborator(long id, long workerId) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
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
                "移除协作者：" + (worker == null ? String.valueOf(workerId) : nullToEmpty(worker.getRealName())));
        if (worker != null) {
            // 通知当事人：他刚失去这张单的可见范围，不告诉他，他会照旧去现场
            notifyCollaboratorChange(ticket, worker, false);
        }
        log.info("移除协作者 ticketId={} workerId={} operator={}", id, workerId, adminId);
    }

    @Override
    @Transactional
    public TicketVO split(long id, TicketSplitDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        if (ticket.getParentTicketId() != null) {
            // 只拆一层：拆出来的单再拆下去会变成一棵谁都说不清的树（docs/01 §4.5）
            throw new BizException(ErrorCode.PARAM_INVALID, "拆出来的工单不能再拆");
        }
        if (!SPLITTABLE_STATUSES.contains(ticket.getStatus())) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    TicketStatus.of(ticket.getStatus()).getDesc() + "的工单不能拆单");
        }
        Long categoryId = dto.getCategoryId() == null ? ticket.getCategoryId() : dto.getCategoryId();
        requireEnabledCategory(categoryId, ticket.getTenantId());

        Ticket created = new Ticket();
        created.setTenantId(ticket.getTenantId());
        created.setTicketNo(nextTicketNo());
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

        return TicketConverter.toVO(created, buildingNames(List.of(created.getBuildingId())),
                categoryNames(List.of(created.getCategoryId())));
    }

    /**
     * 协作动作（加 / 移协作者）的状态门槛：20 待接单 / 30 处理中（`docs/01` §4.5）。
     * 10 还没有主责、40 之后活已经干完，都没有"搭把手"的余地。
     */
    private void requireCollaboratingStatus(Ticket ticket, String action) {
        int status = ticket.getStatus();
        if (status != TicketStatus.TO_ACCEPT.getCode() && status != TicketStatus.PROCESSING.getCode()) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED,
                    TicketStatus.of(status).getDesc() + "的工单不能" + action);
        }
    }

    /** 加/移协作者的通知：**当事人**（他被加进来 / 被移出去，是直接受影响的人）+ **主责**（谁进了这单他该知道）。 */
    private void notifyCollaboratorChange(Ticket ticket, SysUser worker, boolean added) {
        String name = nullToEmpty(worker.getRealName());
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

    /** 拆单通知：**学生**（他报的一单变成了两张）+ **原主责**（若已派了人：他的活少了一半）。 */
    private void notifySplit(Ticket source, Ticket created) {
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

    /** 通知里的位置文案：楼栋名 + 房间号（楼栋查不到时只给房间号，不编造）。 */
    private String location(Ticket ticket) {
        String building = buildingNames(List.of(ticket.getBuildingId())).get(ticket.getBuildingId());
        return (building == null ? "" : building) + ticket.getRoom();
    }

    /**
     * 派单 / 转派共用的目标校验：必须是**本租户**、启用中的维修工。
     *
     * <p>跨租户的师傅与"不存在的师傅"返回同一个错误：不告诉调用方"这个师傅是别家的"。
     */
    private SysUser requireEnabledWorker(Long workerId, Long tenantId) {
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
    private String crossBuildingTrace(Long tenantId, long buildingId, long workerId) {
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

    @Override
    @Transactional
    public void close(long id) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.CLOSED.getCode());

        transitions.conditionalUpdate(id, TicketStatus.CLOSED.getCode(), TicketAction.CLOSE, adminId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> entity.setCloseTime(LocalDateTime.now()));
        // 人工关闭后取消已登记的验收超时任务，避免调度器重复处理
        timeoutService.cancel(id);
    }

    @Override
    @Transactional
    public void autoClose(long id) {
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.CLOSED.getCode());
        transitions.conditionalUpdate(id, TicketStatus.CLOSED.getCode(), TicketAction.AUTO_CLOSE, SYSTEM_OPERATOR,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> entity.setCloseTime(LocalDateTime.now()));
        transitions.notifyTransition(ticket, TicketAction.AUTO_CLOSE, null, null);
        timeoutService.cancel(id);
    }

    @Override
    @Transactional
    public void remindAcceptTimeout(long id) {
        Ticket ticket = requireTicket(id);
        notifyTimeoutOnce(ticket, TicketStatus.TO_ACCEPT.getCode(), TicketAction.ACCEPT_TIMEOUT,
                NOTICE_ACCEPT_TIMEOUT, TITLE_ACCEPT_TIMEOUT,
                "工单 " + ticket.getTicketNo() + " 已超过 " + timeoutRule.acceptThresholdText()
                        + " 无人接单，请及时调度");
    }

    @Override
    @Transactional
    public void escalateProcessTimeout(long id) {
        Ticket ticket = requireTicket(id);
        notifyTimeoutOnce(ticket, TicketStatus.PROCESSING.getCode(), TicketAction.PROCESS_TIMEOUT,
                NOTICE_PROCESS_TIMEOUT, TITLE_PROCESS_TIMEOUT,
                "工单 " + ticket.getTicketNo() + " 已超过 " + timeoutRule.processThresholdText()
                        + " 仍未完工，请及时跟进");
    }

    /**
     * 超时提醒类动作的公共骨架：状态仍停在预期节点 + 之前没提醒过 → 记一笔日志并发通知给调度方。
     *
     * <p>两件事都靠 ticket_log：日志既是"提醒过"的判重依据（兜底扫描每分钟都会扫到同一批超期工单，
     * 不判重会把管理员刷屏），也是"提醒动作发生过"的可追溯记录。
     */
    private void notifyTimeoutOnce(Ticket ticket, int expectedStatus, TicketAction action,
                                   String noticeType, String noticeTitle, String content) {
        if (ticket.getStatus() != expectedStatus) {
            // 已流转（接单/完工/驳回/关闭）——提醒没有意义，静默跳过
            return;
        }
        if (notifiedBefore(ticket.getTenantId(), ticket.getId(), action)) {
            return;
        }
        transitions.writeLog(ticket.getTenantId(), ticket.getId(), ticket.getStatus(), ticket.getStatus(),
                action, SYSTEM_OPERATOR, null);
        int sent = notificationService.sendToTenantAdmins(ticket.getTenantId(), noticeType,
                noticeTitle, content, ticket.getId());
        log.info("{} ticketId={} 送达后勤管理员 {} 人", action.getDesc(), ticket.getId(), sent);
    }

    /** 幂等判据：ticket_log 里已有该动作的记录（日志本身就是"已处理过"的事实依据）。 */
    private boolean notifiedBefore(Long tenantId, long ticketId, TicketAction action) {
        return ticketLogMapper.selectCount(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTenantId, tenantId)
                .eq(TicketLog::getTicketId, ticketId)
                .eq(TicketLog::getAction, action.name())) > 0;
    }

    // ==================== 私有工具 ====================

    private boolean isNotAssignee(Ticket ticket, long workerId) {
        return ticket.getWorkerId() == null || ticket.getWorkerId() != workerId;
    }

    /**
     * 是不是这单的**参与人**：主责 或 协作者。协作者能到场、能完工；接单与驳回仍只给主责
     * ——那两件事是"我认领这单"和"这单不该我做"，属于处置权（`docs/01` §4.5）。
     */
    private boolean isNotParticipant(Ticket ticket, long workerId) {
        return isNotAssignee(ticket, workerId) && !isCollaborator(ticket, workerId);
    }

    /** 这个人在不在这单的协作者名单里。带 tenant_id 条件——防御纵深，见 {@link #collaboratedTicketIds}。 */
    private boolean isCollaborator(Ticket ticket, long workerId) {
        return ticketCollaboratorMapper.selectCount(Wrappers.<TicketCollaborator>lambdaQuery()
                .eq(TicketCollaborator::getTenantId, ticket.getTenantId())
                .eq(TicketCollaborator::getTicketId, ticket.getId())
                .eq(TicketCollaborator::getWorkerId, workerId)) > 0;
    }

    private long collaboratorCount(Ticket ticket) {
        return ticketCollaboratorMapper.selectCount(Wrappers.<TicketCollaborator>lambdaQuery()
                .eq(TicketCollaborator::getTenantId, ticket.getTenantId())
                .eq(TicketCollaborator::getTicketId, ticket.getId()));
    }

    /**
     * 把"参与人"条件拼进条件更新的 WHERE：{@code (worker_id = 我 OR EXISTS(我在协作者里))}。
     *
     * <p><b>必须与 {@link #isNotParticipant} 是同一个口径</b>——两边不一致就会出现
     * "检查过了、但更新 0 行"，对外表现是一句莫名其妙的 20002。
     *
     * <p>为什么用 EXISTS 而不是"先把协作者的单查出来再 IN"：协作者数量没有上界（历史协作一直累积），
     * 而 `idx_worker(worker_id, ticket_id)` 让这条子查询在 ticket_collaborator 上走索引。
     * 子查询里引用外层 {@code ticket.id} 在 MySQL 里是允许的（被更新的表是 ticket，子查询查的是另一张表）。
     */
    private void appendParticipantCondition(LambdaUpdateWrapper<Ticket> wrapper, long workerId) {
        wrapper.and(w -> w.eq(Ticket::getWorkerId, workerId)
                .or().exists("SELECT 1 FROM ticket_collaborator c"
                        + " WHERE c.ticket_id = ticket.id AND c.worker_id = {0}", workerId));
    }

    /** 详情里的协作者名单：一次查询 + 一次批量取姓名，不做 N+1。 */
    private List<TicketCollaboratorVO> collaboratorsOf(Ticket ticket) {
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

    /**
     * 本页里"我参与协作"的工单 ID（给列表打「协作」标记用）。一次查询，不逐条回库。
     *
     * <p>条件里只带 worker_id 不带 tenant_id，与上面查 `worker_building` 同一个理由：
     * worker_id 是全局唯一的雪花 ID（不可能命中别家租户的人），而工单 ID 取自**已经过租户过滤**的一页。
     */
    private Set<Long> collaboratedTicketIds(Long tenantId, long workerId, List<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Set.of();
        }
        return ticketCollaboratorMapper.selectList(Wrappers.<TicketCollaborator>lambdaQuery()
                        .eq(TicketCollaborator::getTenantId, tenantId)
                        .eq(TicketCollaborator::getWorkerId, workerId)
                        .in(TicketCollaborator::getTicketId, ticketIds))
                .stream().map(TicketCollaborator::getTicketId).collect(Collectors.toSet());
    }

    private Ticket requireTicket(long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            // 查不到 = 不存在，或不在当前用户的数据范围内——对外统一说"不存在"，不泄露差别
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }
        return ticket;
    }

    private SysUser requireUser(long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
        return user;
    }

    private RepairCode requireRepairCode(String code, Long tenantId) {
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
    private void requireEnabledBuilding(Long buildingId, Long tenantId) {
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
    private TicketCategory requireEnabledCategory(Long categoryId, Long tenantId) {
        TicketCategory category = ticketCategoryMapper.selectById(categoryId);
        if (category == null || !category.getTenantId().equals(tenantId)
                || !Integer.valueOf(1).equals(category.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报修类别不存在或已停用");
        }
        return category;
    }

    /** 工单号：WX + 日期 + 当日序号（Redis INCR），uk_ticket_no 兜底唯一。 */
    private String nextTicketNo() {
        String date = LocalDate.now().format(TICKET_NO_DATE);
        String seqKey = "ticket:no:seq:" + date;
        Long seq = stringRedisTemplate.opsForValue().increment(seqKey);
        stringRedisTemplate.expire(seqKey, Duration.ofDays(2));
        return "WX" + date + String.format("%06d", seq);
    }

    /** 批量 id → 楼栋名（selectByIds 一次拿全，避免循环回库的 N+1）。 */
    private Map<Long, String> buildingNames(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return buildingMapper.selectByIds(distinct).stream()
                .collect(Collectors.toMap(Building::getId, Building::getName));
    }

    private Map<Long, String> categoryNames(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return ticketCategoryMapper.selectByIds(distinct).stream()
                .collect(Collectors.toMap(TicketCategory::getId, TicketCategory::getName));
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
