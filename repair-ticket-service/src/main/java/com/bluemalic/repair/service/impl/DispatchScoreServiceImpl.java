package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.config.DispatchProperties;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.entity.WorkerGroupMember;
import com.bluemalic.repair.entity.WorkerShift;
import com.bluemalic.repair.entity.WorkerSkill;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.DispatchMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.mapper.WorkerGroupMemberMapper;
import com.bluemalic.repair.mapper.WorkerShiftMapper;
import com.bluemalic.repair.mapper.WorkerSkillMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.DispatchScoreService;
import com.bluemalic.repair.vo.DispatchCandidateVO;
import com.bluemalic.repair.vo.WorkerScoreStatVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 派单评分（阶段 3 · ADR-019）。四项加权、逐项可解释、缺数据时中性降级。
 *
 * <p><b>为什么是这四个维度</b>：技能（干得了）、满意度（干得好）、负载（现在忙不忙）、
 * 距离（去得方不方便）。前两项来自数据积累，后两项来自实时状态——它们正好覆盖
 * "能不能干 / 干得好不好 / 该不该现在派 / 跑一趟值不值"。
 *
 * <p><b>缺数据时的降级规则</b>（每一条都决定了排序是否公平）：
 * <ul>
 *   <li>师傅**没有任何技能记录** → 技能项记 0.5 中性分，而不是 0——否则新师傅永远垫底</li>
 *   <li>师傅**没有评价** → 满意度记 0.5 中性分，同理</li>
 *   <li>师傅**没设负责楼栋** → 距离按最差处理（0.2）并在明细里写明，让人一眼看出是数据没维护</li>
 * </ul>
 * 这四条合起来的原则是：**缺数据不等于差**，但"缺数据"本身要在明细里可见。
 *
 * <p><b>总分按权重之和归一化</b>：学校把权重改成 4/3/2/1 也不影响排序（见 {@link DispatchProperties}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchScoreServiceImpl implements DispatchScoreService {

    /** 缺数据时的中性分（技能 / 满意度） */
    private static final double NEUTRAL = 0.5;

    private static final double DISTANCE_RESPONSIBLE = 1.0;
    private static final double DISTANCE_SAME_AREA = 0.6;
    private static final double DISTANCE_WORST = 0.2;

    /** 满意度归一化用的评分区间（1~5 分制，见 `ticket_evaluation.score`） */
    private static final double SCORE_MIN = 1.0;
    private static final double SCORE_MAX = 5.0;

    private final TicketMapper ticketMapper;
    private final SysUserMapper sysUserMapper;
    private final BuildingMapper buildingMapper;
    private final WorkerBuildingMapper workerBuildingMapper;
    private final WorkerSkillMapper workerSkillMapper;
    private final WorkerGroupMemberMapper workerGroupMemberMapper;
    private final WorkerShiftMapper workerShiftMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final DispatchMapper dispatchMapper;
    private final CurrentTenantService currentTenantService;
    private final DispatchProperties properties;

    @Override
    public List<DispatchCandidateVO> candidates(long ticketId, boolean includeOffDuty) {
        long tenantId = currentTenantService.requireTenantId();

        // 工单走拦截器（租户 + 角色）：查不到就是"不存在或不在你范围内"
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BizException(ErrorCode.TICKET_NOT_FOUND);
        }

        // 候选人 = 本租户启用中的全部师傅（跨楼栋派单是允许的，所以不按楼栋过滤）
        List<SysUser> workers = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUserType, UserType.WORKER.getCode())
                .eq(SysUser::getStatus, 1)
                .orderByAsc(SysUser::getUsername));
        if (workers.isEmpty()) {
            return List.of();
        }
        List<Long> workerIds = workers.stream().map(SysUser::getId).toList();

        Map<Long, List<Long>> buildingsByWorker = workerBuildingMapper.selectList(
                        Wrappers.<WorkerBuilding>lambdaQuery()
                                .eq(WorkerBuilding::getTenantId, tenantId)
                                .in(WorkerBuilding::getWorkerId, workerIds))
                .stream()
                .collect(Collectors.groupingBy(WorkerBuilding::getWorkerId,
                        Collectors.mapping(WorkerBuilding::getBuildingId, Collectors.toList())));

        Map<Long, List<Long>> skillsByWorker = workerSkillMapper.selectList(
                        Wrappers.<WorkerSkill>lambdaQuery()
                                .eq(WorkerSkill::getTenantId, tenantId)
                                .in(WorkerSkill::getWorkerId, workerIds))
                .stream()
                .collect(Collectors.groupingBy(WorkerSkill::getWorkerId,
                        Collectors.mapping(WorkerSkill::getCategoryId, Collectors.toList())));

        Map<Long, List<Long>> groupsByWorker = workerGroupMemberMapper.selectList(
                        Wrappers.<WorkerGroupMember>lambdaQuery()
                                .eq(WorkerGroupMember::getTenantId, tenantId)
                                .in(WorkerGroupMember::getWorkerId, workerIds))
                .stream()
                .collect(Collectors.groupingBy(WorkerGroupMember::getWorkerId,
                        Collectors.mapping(WorkerGroupMember::getGroupId, Collectors.toList())));

        // 只取今天的排班：「没有记录」与「明确休息」在评分里是两种结果
        Map<Long, List<WorkerShift>> shiftsByWorker = workerShiftMapper.selectList(
                        Wrappers.<WorkerShift>lambdaQuery()
                                .eq(WorkerShift::getTenantId, tenantId)
                                .eq(WorkerShift::getShiftDate, LocalDate.now())
                                .in(WorkerShift::getWorkerId, workerIds))
                .stream()
                .collect(Collectors.groupingBy(WorkerShift::getWorkerId));

        Map<Long, WorkerScoreStatVO> stats = dispatchMapper.loadStats(tenantId, workerIds).stream()
                .collect(Collectors.toMap(WorkerScoreStatVO::getWorkerId, Function.identity()));

        // 楼栋与类别都按租户整体取一次：两张小表，换来"展示名 + 区域判断"不产生任何 N+1
        Map<Long, Building> buildings = buildingMapper.selectList(Wrappers.<Building>lambdaQuery()
                        .eq(Building::getTenantId, tenantId))
                .stream().collect(Collectors.toMap(Building::getId, Function.identity()));
        Map<Long, TicketCategory> categories = ticketCategoryMapper.selectList(
                        Wrappers.<TicketCategory>lambdaQuery().eq(TicketCategory::getTenantId, tenantId))
                .stream().collect(Collectors.toMap(TicketCategory::getId, Function.identity()));
        Map<Long, String> categoryNames = categories.values().stream()
                .collect(Collectors.toMap(TicketCategory::getId, TicketCategory::getName));

        Building ticketBuilding = buildings.get(ticket.getBuildingId());
        TicketCategory ticketCategory = categories.get(ticket.getCategoryId());
        String ticketCategoryName = categoryNames.get(ticket.getCategoryId());
        Long categoryGroupId = ticketCategory == null ? null : ticketCategory.getWorkerGroupId();

        List<DispatchCandidateVO> candidates = new ArrayList<>(workers.size());
        for (SysUser worker : workers) {
            List<WorkerShift> workerShifts = shiftsByWorker.getOrDefault(worker.getId(), List.of());
            boolean explicitlyOff = !workerShifts.isEmpty()
                    && workerShifts.stream().noneMatch(shift -> Integer.valueOf(1).equals(shift.getStatus()));
            if (!includeOffDuty && explicitlyOff) {
                continue;
            }
            candidates.add(score(worker, ticket, ticketBuilding, ticketCategoryName, categoryGroupId,
                    buildingsByWorker.getOrDefault(worker.getId(), List.of()),
                    skillsByWorker.getOrDefault(worker.getId(), List.of()),
                    groupsByWorker.getOrDefault(worker.getId(), List.of()),
                    workerShifts, buildings, categoryNames,
                    stats.get(worker.getId())));
        }

        candidates.sort(Comparator
                .comparingDouble(DispatchCandidateVO::getScore).reversed()
                .thenComparingInt(DispatchCandidateVO::getActiveCount)
                .thenComparing(DispatchCandidateVO::getWorkerId));

        log.info("派单候选 ticketId={} tenantId={} 含离岗={} 候选={} 首位={} 总分={}",
                ticketId, tenantId, includeOffDuty, candidates.size(),
                candidates.isEmpty() ? "-" : candidates.get(0).getWorkerName(),
                candidates.isEmpty() ? "-" : candidates.get(0).getScore());
        return candidates;
    }

    // ==================== 单个候选人的五项评分 ====================

    private DispatchCandidateVO score(SysUser worker, Ticket ticket, Building ticketBuilding,
                                      String ticketCategoryName, Long categoryGroupId,
                                      List<Long> buildingIds, List<Long> skillCategoryIds,
                                      List<Long> groupIds, List<WorkerShift> shifts,
                                      Map<Long, Building> buildings, Map<Long, String> categoryNames,
                                      WorkerScoreStatVO stat) {
        int activeCount = stat == null || stat.getActiveCount() == null ? 0 : stat.getActiveCount();
        Double avgScore = stat == null ? null : stat.getAvgScore();

        List<DispatchCandidateVO.ScoreItem> items = List.of(
                skillItem(ticket.getCategoryId(), ticketCategoryName, skillCategoryIds, groupIds, categoryGroupId),
                satisfactionItem(avgScore),
                loadItem(activeCount),
                distanceItem(ticket.getBuildingId(), ticketBuilding, buildingIds, buildings),
                shiftItem(shifts));

        double total = items.stream()
                .mapToDouble(item -> item.getScore() * item.getWeight())
                .sum() / properties.weightSum();

        DispatchCandidateVO vo = new DispatchCandidateVO();
        vo.setWorkerId(worker.getId());
        vo.setUsername(worker.getUsername());
        vo.setWorkerName(worker.getRealName());
        vo.setBuildingNames(buildingIds.stream()
                .map(buildings::get).filter(Objects::nonNull).map(Building::getName).toList());
        vo.setSkillCategoryNames(skillCategoryIds.stream()
                .map(categoryNames::get).filter(Objects::nonNull).toList());
        vo.setResponsibleForBuilding(buildingIds.contains(ticket.getBuildingId()));
        vo.setActiveCount(activeCount);
        vo.setAvgScore(avgScore);
        vo.setOnDutyToday(shifts.isEmpty() ? null
                : shifts.stream().anyMatch(shift -> Integer.valueOf(1).equals(shift.getStatus())));
        vo.setScore(Math.round(total * 1000) / 1000.0);
        vo.setItems(items);
        // 理由就是五项明细拼起来：可解释性不靠"模型润色"，靠数据本身
        vo.setReason(items.stream().map(DispatchCandidateVO.ScoreItem::getDetail)
                .collect(Collectors.joining("、")));
        return vo;
    }

    /**
     * 技能：擅长该类别 1.0；类别的默认工种与师傅所属工种一致 1.0；
     * 有技能/工种数据但不匹配 0.0；**完全没有任何数据记中性 0.5**（缺数据不等于差）。
     */
    private DispatchCandidateVO.ScoreItem skillItem(Long ticketCategoryId, String categoryName,
                                                    List<Long> skillCategoryIds, List<Long> groupIds,
                                                    Long categoryGroupId) {
        String name = StringUtils.hasText(categoryName) ? categoryName : "该类别";
        if (ticketCategoryId != null && skillCategoryIds.contains(ticketCategoryId)) {
            return item("skill", "擅长类别", 1.0, properties.getWeightSkill(), "擅长" + name);
        }
        if (categoryGroupId != null && groupIds.contains(categoryGroupId)) {
            return item("skill", "擅长类别", 1.0, properties.getWeightSkill(), "属于" + name + "对应工种");
        }
        if (skillCategoryIds.isEmpty() && groupIds.isEmpty()) {
            return item("skill", "擅长类别", NEUTRAL, properties.getWeightSkill(), "未维护擅长类别/工种");
        }
        return item("skill", "擅长类别", 0.0, properties.getWeightSkill(), "不擅长" + name);
    }

    /** 满意度：1~5 分归一化到 0~1；**没有评价记中性 0.5**（新师傅不该因此垫底）。 */
    private DispatchCandidateVO.ScoreItem satisfactionItem(Double avgScore) {
        if (avgScore == null) {
            return item("satisfaction", "满意度", NEUTRAL, properties.getWeightSatisfaction(), "暂无评价");
        }
        double normalized = (avgScore - SCORE_MIN) / (SCORE_MAX - SCORE_MIN);
        normalized = Math.max(0, Math.min(1, normalized));
        return item("satisfaction", "满意度", normalized, properties.getWeightSatisfaction(),
                String.format("历史平均 %.1f 分", avgScore));
    }

    /** 负载：进行中单数越少越好；到封顶单数记 0 分，不出现负分。 */
    private DispatchCandidateVO.ScoreItem loadItem(int activeCount) {
        double score = Math.max(0, 1.0 - (double) activeCount / properties.getLoadCap());
        return item("load", "当前负载", score, properties.getWeightLoad(), "当前 " + activeCount + " 单");
    }

    /** 值班：今天明确在班 1.0；今天明确请假/休息 0.0；**今天没排班记录记中性 0.5**。 */
    private DispatchCandidateVO.ScoreItem shiftItem(List<WorkerShift> shifts) {
        if (shifts.isEmpty()) {
            return item("shift", "今日值班", NEUTRAL, properties.getWeightShift(),
                    "今日未排班（按缺数据计中性）");
        }
        boolean onDuty = shifts.stream().anyMatch(shift -> Integer.valueOf(1).equals(shift.getStatus()));
        if (!onDuty) {
            return item("shift", "今日值班", 0.0, properties.getWeightShift(), "今日请假/休息");
        }
        String types = shifts.stream()
                .filter(shift -> Integer.valueOf(1).equals(shift.getStatus()))
                .map(shift -> Integer.valueOf(2).equals(shift.getShiftType()) ? "夜班" : "白班")
                .distinct().collect(Collectors.joining("/"));
        return item("shift", "今日值班", 1.0, properties.getWeightShift(), "今日在班（" + types + "）");
    }

    /**
     * 距离：项目没有坐标，这里用**楼栋归属做代理**（ADR-019 明确写了"不是真实里程"）——
     * 负责该楼栋最优、同区次之、其余最差。
     */
    private DispatchCandidateVO.ScoreItem distanceItem(long ticketBuildingId, Building ticketBuilding,
                                                       List<Long> buildingIds, Map<Long, Building> buildings) {
        if (buildingIds.contains(ticketBuildingId)) {
            return item("distance", "距离（楼栋归属）", DISTANCE_RESPONSIBLE,
                    properties.getWeightDistance(), "负责该楼栋");
        }
        if (buildingIds.isEmpty()) {
            return item("distance", "距离（楼栋归属）", DISTANCE_WORST,
                    properties.getWeightDistance(), "未设置负责楼栋");
        }
        boolean sameArea = ticketBuilding != null && buildingIds.stream()
                .map(buildings::get).filter(Objects::nonNull)
                .anyMatch(building -> Objects.equals(building.getArea(), ticketBuilding.getArea()));
        if (sameArea) {
            return item("distance", "距离（楼栋归属）", DISTANCE_SAME_AREA,
                    properties.getWeightDistance(), "同区（" + ticketBuilding.getArea() + "）");
        }
        return item("distance", "距离（楼栋归属）", DISTANCE_WORST,
                properties.getWeightDistance(), "跨区");
    }

    private DispatchCandidateVO.ScoreItem item(String key, String label, double score, double weight,
                                               String detail) {
        DispatchCandidateVO.ScoreItem item = new DispatchCandidateVO.ScoreItem();
        item.setKey(key);
        item.setLabel(label);
        item.setScore(Math.round(score * 1000) / 1000.0);
        item.setWeight(weight);
        item.setDetail(detail);
        return item;
    }

}
