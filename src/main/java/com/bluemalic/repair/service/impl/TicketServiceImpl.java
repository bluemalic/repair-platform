package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.converter.TicketConverter;
import com.bluemalic.repair.dto.TicketArriveDTO;
import com.bluemalic.repair.dto.TicketCreateDTO;
import com.bluemalic.repair.dto.TicketDispatchDTO;
import com.bluemalic.repair.dto.TicketEvaluateDTO;
import com.bluemalic.repair.dto.TicketFinishDTO;
import com.bluemalic.repair.dto.TicketRejectDTO;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.NotificationService;
import com.bluemalic.repair.service.TicketService;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.RepairCodeVO;
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
import java.util.function.Consumer;
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

    /** 超时提醒类通知（不是状态跃迁，所以不进 TRANSITION_NOTICE 表）：类型与标题放一处。 */
    private static final String NOTICE_ACCEPT_TIMEOUT = "TICKET_ACCEPT_TIMEOUT";
    private static final String TITLE_ACCEPT_TIMEOUT = "工单超时未接单";
    private static final String NOTICE_PROCESS_TIMEOUT = "TICKET_PROCESS_TIMEOUT";
    private static final String TITLE_PROCESS_TIMEOUT = "工单处理超时升级";

    private final TicketMapper ticketMapper;
    private final TicketLogMapper ticketLogMapper;
    private final TicketEvaluationMapper ticketEvaluationMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final BuildingMapper buildingMapper;
    private final RepairCodeMapper repairCodeMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
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

        TicketCategory category = ticketCategoryMapper.selectById(dto.getCategoryId());
        if (category == null || !category.getTenantId().equals(student.getTenantId())
                || !Integer.valueOf(1).equals(category.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报修类别不存在或已停用");
        }

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

        writeLog(ticket.getTenantId(), ticket.getId(), null, TicketStatus.TO_DISPATCH.getCode(),
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
                new Page<>(clamp(pageNum), clamp(pageSize)),
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
    public TicketDetailVO detail(long id) {
        Ticket ticket = requireTicket(id);
        Map<Long, String> buildings = buildingNames(List.of(ticket.getBuildingId()));
        Map<Long, String> categories = categoryNames(List.of(ticket.getCategoryId()));

        List<TicketLog> logs = ticketLogMapper.selectList(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTicketId, id).orderByAsc(TicketLog::getCreateTime));
        Map<Long, String> operators = sysUserMapper.selectByIds(
                        logs.stream().map(TicketLog::getOperatorId).distinct().toList()).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> nullToEmpty(u.getRealName())));
        List<TicketLogVO> logVOs = logs.stream()
                .map(l -> TicketConverter.toLogVO(l, operators)).toList();

        TicketEvaluation evaluation = ticketEvaluationMapper.selectOne(
                Wrappers.<TicketEvaluation>lambdaQuery().eq(TicketEvaluation::getTicketId, id));

        return TicketConverter.toDetailVO(ticket, buildings, categories, logVOs, evaluation);
    }

    @Override
    @Transactional
    public void cancel(long id) {
        long studentId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.CANCELED.getCode());

        // 学生只能撤自己的单——数据范围由拦截器注入到 UPDATE 里，这里不再手写 student_id 条件
        conditionalUpdate(id, TicketStatus.CANCELED.getCode(), TicketAction.CANCEL, studentId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> entity.setCloseTime(LocalDateTime.now()));
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

        conditionalUpdate(id, TicketStatus.FINISHED.getCode(), TicketAction.EVALUATE, studentId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> { });
        notifyTransition(ticket, TicketAction.EVALUATE, null, "已被评价 " + dto.getScore() + " 分");
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
        conditionalUpdate(id, TicketStatus.PROCESSING.getCode(), TicketAction.ACCEPT, workerId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus())
                        .eq(Ticket::getWorkerId, workerId),
                entity -> entity.setAcceptTime(LocalDateTime.now()));
        // 接单节点完成：撤掉未接单提醒；同时登记"未处理升级"（自派单起算 48h 未完工）
        timeoutService.cancel(id);
        timeoutService.registerProcess(id);
        notifyTransition(ticket, TicketAction.ACCEPT, null, null);
    }

    @Override
    @Transactional
    public void arrive(long id, TicketArriveDTO dto) {
        long workerId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        if (ticket.getStatus() != TicketStatus.PROCESSING.getCode()) {
            throw new BizException(ErrorCode.TICKET_STATUS_NOT_ALLOWED, "工单不在处理中，无法到场打卡");
        }
        if (isNotAssignee(ticket, workerId)) {
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
        conditionalUpdate(id, ticket.getStatus(), TicketAction.ARRIVE, workerId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus())
                        .eq(Ticket::getWorkerId, workerId)
                        .isNull(Ticket::getArriveTime),
                entity -> {
                    entity.setArriveTime(now);
                    entity.setArriveMinutes(arriveMinutes);
                });
        notifyTransition(ticket, TicketAction.ARRIVE, null, null);
    }

    @Override
    @Transactional
    public void finish(long id, TicketFinishDTO dto) {
        long workerId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.TO_VERIFY.getCode());
        if (isNotAssignee(ticket, workerId)) {
            throw new BizException(ErrorCode.TICKET_ALREADY_ACCEPTED);
        }

        LocalDateTime now = LocalDateTime.now();
        Integer handleMinutes = ticket.getArriveTime() != null
                ? (int) Duration.between(ticket.getArriveTime(), now).toMinutes() : null;
        conditionalUpdate(id, TicketStatus.TO_VERIFY.getCode(), TicketAction.FINISH, workerId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus())
                        .eq(Ticket::getWorkerId, workerId),
                entity -> {
                    entity.setFinishTime(now);
                    entity.setResultDesc(dto.getResultDesc());
                    entity.setResultImages(dto.getResultImages());
                    entity.setHandleMinutes(handleMinutes);
                });
        // 完工：处理节点完成，撤掉未处理升级登记（后续由验收超时节点接管）
        timeoutService.cancel(id);
        notifyTransition(ticket, TicketAction.FINISH, null, null);
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
        notifyTransition(ticket, TicketAction.REJECT, null, "被驳回：" + dto.getReason());
    }

    @Override
    @Transactional
    public void rejectByAdmin(long id, TicketRejectDTO dto) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.REJECTED.getCode());
        doReject(ticket, adminId, dto.getReason());
        notifyTransition(ticket, TicketAction.REJECT, ticket.getStudentId(), "被驳回：" + dto.getReason());
        notifyTransition(ticket, TicketAction.REJECT, ticket.getWorkerId(), "被驳回：" + dto.getReason());
    }

    private void doReject(Ticket ticket, long operatorId, String reason) {
        conditionalUpdate(ticket.getId(), TicketStatus.REJECTED.getCode(), TicketAction.REJECT, operatorId,
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

        SysUser worker = sysUserMapper.selectById(dto.getWorkerId());
        if (worker == null || !Integer.valueOf(2).equals(worker.getUserType())
                || !Integer.valueOf(1).equals(worker.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "维修工不存在或已停用");
        }

        conditionalUpdate(id, TicketStatus.TO_ACCEPT.getCode(), TicketAction.DISPATCH, adminId,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> {
                    entity.setWorkerId(dto.getWorkerId());
                    entity.setDispatchType(1);
                    entity.setDispatchTime(LocalDateTime.now());
                });
        notifyTransition(ticket, TicketAction.DISPATCH, dto.getWorkerId(), null);
        // 登记未接单提醒：到期仍无人接单就提醒调度方（M3）；重新派单会覆盖到期时间
        timeoutService.registerAccept(id);
        log.info("派单 ticketId={} workerId={} operator={}", id, dto.getWorkerId(), adminId);
    }

    @Override
    @Transactional
    public void close(long id) {
        long adminId = StpUtil.getLoginIdAsLong();
        Ticket ticket = requireTicket(id);
        TicketStatus.checkTransition(ticket.getStatus(), TicketStatus.CLOSED.getCode());

        conditionalUpdate(id, TicketStatus.CLOSED.getCode(), TicketAction.CLOSE, adminId,
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
        conditionalUpdate(id, TicketStatus.CLOSED.getCode(), TicketAction.AUTO_CLOSE, SYSTEM_OPERATOR,
                ticket.getTenantId(), ticket.getStatus(),
                wrapper -> wrapper.eq(Ticket::getStatus, ticket.getStatus()),
                entity -> entity.setCloseTime(LocalDateTime.now()));
        notifyTransition(ticket, TicketAction.AUTO_CLOSE, null, null);
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
        if (notifiedBefore(ticket.getId(), action)) {
            return;
        }
        writeLog(ticket.getTenantId(), ticket.getId(), ticket.getStatus(), ticket.getStatus(),
                action, SYSTEM_OPERATOR, null);
        int sent = notificationService.sendToTenantAdmins(ticket.getTenantId(), noticeType,
                noticeTitle, content, ticket.getId());
        log.info("{} ticketId={} 送达后勤管理员 {} 人", action.getDesc(), ticket.getId(), sent);
    }

    /** 幂等判据：ticket_log 里已有该动作的记录（日志本身就是"已处理过"的事实依据）。 */
    private boolean notifiedBefore(long ticketId, TicketAction action) {
        return ticketLogMapper.selectCount(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTicketId, ticketId)
                .eq(TicketLog::getAction, action.name())) > 0;
    }

    // ==================== 私有工具 ====================

    /** 状态变更通知的规格：类型、标题、正文后缀、默认接收方。满载等新动作加一行即可。 */
    private record NoticeSpec(String type, String title, String suffix, boolean toStudent) {
    }

    /** 动作 → 通知规格。通知的文案与接收方收敛在这里，调用方只负责"触发"与必要的补充参数。 */
    private static final Map<TicketAction, NoticeSpec> TRANSITION_NOTICE = Map.of(
            TicketAction.ACCEPT,   new NoticeSpec("TICKET_ACCEPTED",  "维修工已接单",     "已被接单，维修工会尽快到场", true),
            TicketAction.ARRIVE,   new NoticeSpec("TICKET_ARRIVED",   "维修工已到场",     "维修工已到场处理",           true),
            TicketAction.FINISH,   new NoticeSpec("TICKET_FINISHED",  "维修完成待验收",   "已完成维修，请验收评价",     true),
            TicketAction.REJECT,   new NoticeSpec("TICKET_REJECTED",  "工单被驳回",       null,                          true),
            TicketAction.DISPATCH, new NoticeSpec("TICKET_DISPATCHED","新工单待接单",     "已派给你，请及时接单",       false),
            TicketAction.EVALUATE, new NoticeSpec("TICKET_EVALUATED", "工单已验收",       null,                          false),
            TicketAction.AUTO_CLOSE, new NoticeSpec("TICKET_AUTO_CLOSED", "工单已自动关闭", "验收后超时未关闭，工单已自动关闭", true));

    /**
     * 按动作给相关方发站内通知。默认接收方取自工单上的学生/维修工；
     * explicitReceiver 非空时优先——派单时工单还没写维修工、管理员驳回时维修工已被清空，
     * 这两种场景由调用方把"该收通知的人"传进来，而不是事后回查。
     */
    private void notifyTransition(Ticket ticket, TicketAction action, Long explicitReceiver, String suffix) {
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
     */
    private void conditionalUpdate(long id, int toStatus, TicketAction action, long operatorId,
                                   Long tenantId, int fromStatus,
                                   Consumer<LambdaUpdateWrapper<Ticket>> where,
                                   Consumer<Ticket> entitySetter) {
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
        writeLog(tenantId, id, fromStatus, toStatus, action, operatorId, entity.getRejectReason());
    }

    private void writeLog(Long tenantId, long ticketId, Integer fromStatus, int toStatus,
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

    private boolean isNotAssignee(Ticket ticket, long workerId) {
        return ticket.getWorkerId() == null || ticket.getWorkerId() != workerId;
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

    /** 工单号：WX + 日期 + 当日序号（Redis INCR），uk_ticket_no 兜底唯一。 */
    private String nextTicketNo() {
        String date = LocalDate.now().format(TICKET_NO_DATE);
        String seqKey = "ticket:no:seq:" + date;
        Long seq = stringRedisTemplate.opsForValue().increment(seqKey);
        stringRedisTemplate.expire(seqKey, Duration.ofDays(2));
        return "WX" + date + String.format("%06d", seq);
    }

    private long clamp(long value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 100);
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
