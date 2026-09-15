package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.dto.StatisticsQueryDTO;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.mapper.StatisticsMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.service.StatisticsService;
import com.bluemalic.repair.vo.StatisticsDistributionVO;
import com.bluemalic.repair.vo.StatisticsOverviewVO;
import com.bluemalic.repair.vo.StatisticsTrendVO;
import com.bluemalic.repair.vo.StatisticsWorkerWorkloadVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统计看板实现。职责只有三件事：把日期条件补齐成时间区间、把 SQL 出来的原始数字换算成比率、
 * 把需要文案的地方（紧急度）换成中文——真正的聚合都在 Mapper XML 里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 默认统计区间：近 30 天（含今天）。 */
    private static final int DEFAULT_WINDOW_DAYS = 30;
    /** 区间上限：防止把整段历史拉进聚合查询（看板 P99 < 800ms 的要求）。 */
    private static final int MAX_WINDOW_DAYS = 366;

    private static final String GRANULARITY_DAY = "day";
    private static final String GRANULARITY_WEEK = "week";
    private static final Map<String, String> URGENCY_LABELS = Map.of("1", "普通", "2", "紧急", "3", "特急");

    private final StatisticsMapper statisticsMapper;
    private final SysUserMapper sysUserMapper;

    @Override
    public StatisticsOverviewVO overview(StatisticsQueryDTO query) {
        Long tenantId = currentTenantId();
        Range range = resolveRange(query);
        StatisticsOverviewVO vo = statisticsMapper.selectOverview(tenantId, range.start(), range.end());
        int total = vo.getTotal() == null ? 0 : vo.getTotal();
        int timeoutCount = vo.getTimeoutCount() == null ? 0 : vo.getTimeoutCount();
        vo.setTotal(total);
        vo.setTimeoutCount(timeoutCount);
        // 没有工单时超时率给 0（而不是 null）：0% 与"无数据"在这里语义一致，前端不用特判
        vo.setTimeoutRate(total == 0 ? 0D : percent(timeoutCount, total));
        vo.setAvgResponseMinutes(round2(vo.getAvgResponseMinutes()));
        vo.setAvgHandleMinutes(round2(vo.getAvgHandleMinutes()));
        vo.setAvgScore(round2(vo.getAvgScore()));
        return vo;
    }

    @Override
    public List<StatisticsTrendVO> trend(StatisticsQueryDTO query) {
        Long tenantId = currentTenantId();
        Range range = resolveRange(query);
        String granularity = query.getGranularity() == null ? GRANULARITY_DAY : query.getGranularity();
        List<StatisticsTrendVO> points = switch (granularity) {
            case GRANULARITY_DAY -> fillMissingDays(
                    statisticsMapper.selectTrendByDay(tenantId, range.start(), range.end()), range);
            case GRANULARITY_WEEK -> statisticsMapper.selectTrendByWeek(tenantId, range.start(), range.end());
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "granularity 只支持 day / week");
        };
        return points;
    }

    @Override
    public List<StatisticsDistributionVO> distribution(StatisticsQueryDTO query) {
        Long tenantId = currentTenantId();
        Range range = resolveRange(query);
        String dimension = query.getDimension();
        if (dimension == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "dimension 必填：category / building / urgency");
        }
        return switch (dimension) {
            case "category" -> statisticsMapper.selectDistributionByCategory(tenantId, range.start(), range.end());
            case "building" -> statisticsMapper.selectDistributionByBuilding(tenantId, range.start(), range.end());
            case "urgency" -> {
                List<StatisticsDistributionVO> rows =
                        statisticsMapper.selectDistributionByUrgency(tenantId, range.start(), range.end());
                rows.forEach(row -> row.setName(URGENCY_LABELS.getOrDefault(row.getName(), "未知")));
                yield rows;
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID,
                    "dimension 只支持 category / building / urgency");
        };
    }

    @Override
    public List<StatisticsWorkerWorkloadVO> workerWorkload(StatisticsQueryDTO query) {
        Long tenantId = currentTenantId();
        Range range = resolveRange(query);
        List<StatisticsWorkerWorkloadVO> rows =
                statisticsMapper.selectWorkerWorkload(tenantId, range.start(), range.end());
        rows.forEach(row -> {
            row.setAvgHandleMinutes(round2(row.getAvgHandleMinutes()));
            int finished = row.getFinishedCount() == null ? 0 : row.getFinishedCount();
            int timeout = row.getProcessTimeoutCount() == null ? 0 : row.getProcessTimeoutCount();
            // 没有完工工单时按时率给 null：显示"-"比显示 0% 诚实
            row.setOnTimeRate(finished == 0 ? null : percent(finished - timeout, finished));
        });
        return rows;
    }

    // ==================== 私有工具 ====================

    private Long currentTenantId() {
        long userId = StpUtil.getLoginIdAsLong();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
        return user.getTenantId();
    }

    /** 区间左闭右开：[start 00:00, end+1天 00:00)。 */
    private Range resolveRange(StatisticsQueryDTO query) {
        LocalDate end = query.getEnd() == null ? LocalDate.now() : query.getEnd();
        LocalDate start = query.getStart() == null ? end.minusDays(DEFAULT_WINDOW_DAYS - 1L) : query.getStart();
        if (start.isAfter(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "起始日期不能晚于结束日期");
        }
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "时间范围不能超过 " + MAX_WINDOW_DAYS + " 天");
        }
        return new Range(start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    /** 把"没有工单的那天"补成 0：否则前端折线图会把两点直接连起来，看不出断档。 */
    private List<StatisticsTrendVO> fillMissingDays(List<StatisticsTrendVO> rows, Range range) {
        Map<String, Integer> byDate = new java.util.HashMap<>();
        rows.forEach(row -> byDate.put(row.getDate(), row.getCount()));
        List<StatisticsTrendVO> filled = new ArrayList<>();
        for (LocalDate day = range.start().toLocalDate(); day.isBefore(range.end().toLocalDate()); day = day.plusDays(1)) {
            StatisticsTrendVO point = new StatisticsTrendVO();
            point.setDate(day.format(DAY));
            point.setCount(byDate.getOrDefault(day.format(DAY), 0));
            filled.add(point);
        }
        return filled;
    }

    private Double percent(int part, int total) {
        return round2(part * 100.0 / total);
    }

    private Double round2(Double value) {
        return value == null ? null
                : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private record Range(LocalDateTime start, LocalDateTime end) {
    }
}