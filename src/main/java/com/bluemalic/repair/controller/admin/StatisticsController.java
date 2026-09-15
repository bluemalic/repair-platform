package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.StatisticsQueryDTO;
import com.bluemalic.repair.service.StatisticsService;
import com.bluemalic.repair.vo.StatisticsDistributionVO;
import com.bluemalic.repair.vo.StatisticsOverviewVO;
import com.bluemalic.repair.vo.StatisticsTrendVO;
import com.bluemalic.repair.vo.StatisticsWorkerWorkloadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计看板（后勤端）。数据范围 = 当前租户，时间范围缺省近 30 天。
 *
 * <p>权限统一是 {@code statistics:view}（种子里只给了后勤角色）；Excel 导出（docs/03 标 P1）本里程碑不做。
 */
@Tag(name = "后勤管理端-统计看板", description = "报修量、时长、超时率、满意度与师傅工作量")
@SaCheckPermission("statistics:view")
@RestController
@RequestMapping("/api/admin/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @Operation(summary = "核心指标卡",
            description = "总量、平均响应/处理时长、超时率（百分比 0-100）、平均满意度；时间范围缺省近 30 天")
    @GetMapping("/overview")
    public Result<StatisticsOverviewVO> overview(@Parameter(description = "起始日期 yyyy-MM-dd")
                                                 @RequestParam(required = false) LocalDate start,
                                                 @Parameter(description = "结束日期 yyyy-MM-dd")
                                                 @RequestParam(required = false) LocalDate end) {
        StatisticsQueryDTO query = new StatisticsQueryDTO();
        query.setStart(start);
        query.setEnd(end);
        return Result.ok(statisticsService.overview(query));
    }

    @Operation(summary = "报修量趋势", description = "按天或按周（周以周一为起点）；按天会把没有工单的日期补 0")
    @GetMapping("/trend")
    public Result<List<StatisticsTrendVO>> trend(@Parameter(description = "起始日期 yyyy-MM-dd")
                                                 @RequestParam(required = false) LocalDate start,
                                                 @Parameter(description = "结束日期 yyyy-MM-dd")
                                                 @RequestParam(required = false) LocalDate end,
                                                 @Parameter(description = "粒度：day / week")
                                                 @RequestParam(defaultValue = "day") String granularity) {
        StatisticsQueryDTO query = new StatisticsQueryDTO();
        query.setStart(start);
        query.setEnd(end);
        query.setGranularity(granularity);
        return Result.ok(statisticsService.trend(query));
    }

    @Operation(summary = "分布统计", description = "按类别 / 楼栋 / 紧急度统计工单量，降序返回")
    @GetMapping("/distribution")
    public Result<List<StatisticsDistributionVO>> distribution(
            @Parameter(description = "维度：category / building / urgency")
            @RequestParam String dimension,
            @Parameter(description = "起始日期 yyyy-MM-dd")
            @RequestParam(required = false) LocalDate start,
            @Parameter(description = "结束日期 yyyy-MM-dd")
            @RequestParam(required = false) LocalDate end) {
        StatisticsQueryDTO query = new StatisticsQueryDTO();
        query.setStart(start);
        query.setEnd(end);
        query.setDimension(dimension);
        return Result.ok(statisticsService.distribution(query));
    }

    @Operation(summary = "师傅工作量与效率", description = "完工数、平均处理时长、按时完成率（百分制），按完工数降序")
    @GetMapping("/worker-workload")
    public Result<List<StatisticsWorkerWorkloadVO>> workerWorkload(
            @Parameter(description = "起始日期 yyyy-MM-dd")
            @RequestParam(required = false) LocalDate start,
            @Parameter(description = "结束日期 yyyy-MM-dd")
            @RequestParam(required = false) LocalDate end) {
        StatisticsQueryDTO query = new StatisticsQueryDTO();
        query.setStart(start);
        query.setEnd(end);
        return Result.ok(statisticsService.workerWorkload(query));
    }
}