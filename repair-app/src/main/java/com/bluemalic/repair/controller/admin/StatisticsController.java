package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.StatisticsQueryDTO;
import com.bluemalic.repair.interceptor.RateLimit;
import com.bluemalic.repair.service.StatisticsService;
import com.bluemalic.repair.vo.StatisticsDistributionVO;
import com.bluemalic.repair.vo.StatisticsOverviewVO;
import com.bluemalic.repair.vo.StatisticsTrendVO;
import com.bluemalic.repair.vo.StatisticsWorkerWorkloadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * 统计看板（后勤端）。数据范围 = 当前租户，时间范围缺省近 30 天。
 *
 * <p>权限统一是 {@code statistics:view}（种子里只给了后勤角色）。
 */
@Tag(name = "后勤管理端-统计看板", description = "报修量、时长、超时率、满意度与师傅工作量")
@SaCheckPermission("statistics:view")
@RestController
@RequestMapping("/api/admin/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

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

    @Operation(summary = "导出统计报表（Excel）",
            description = "四张工作表（概况 / 报修量趋势 / 分布统计 / 师傅工作量）对应上面四个接口，"
                    + "数字与页面一致；时间范围缺省近 30 天。**失败时返回的是普通 JSON 错误体**——"
                    + "取数在写出第一个字节之前完成，所以范围非法这类错误不会变成半截文件")
    @RateLimit
    @GetMapping("/export")
    public void export(@Parameter(description = "起始日期 yyyy-MM-dd")
                       @RequestParam(required = false) LocalDate start,
                       @Parameter(description = "结束日期 yyyy-MM-dd")
                       @RequestParam(required = false) LocalDate end,
                       HttpServletResponse response) throws IOException {
        StatisticsQueryDTO query = new StatisticsQueryDTO();
        query.setStart(start);
        query.setEnd(end);
        // 这三行顺序不能动：先取数（时间范围校验在这一步），再设响应头，最后才写字节。
        // 反过来的话，"范围超 366 天"这类错误发生时浏览器已经收到 Content-Disposition: attachment，
        // 会把错误 JSON 当 .xlsx 存下来——现象是"下到一个打不开的文件"，很难倒推回原因。
        byte[] workbook = statisticsService.export(query);
        response.setContentType(XLSX_CONTENT_TYPE);
        response.setContentLength(workbook.length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, attachmentName(start, end));
        response.getOutputStream().write(workbook);
    }

    /**
     * 附件名。中文名必须走 RFC 5987 的 {@code filename*}（只有它带得动 UTF-8），
     * 同时给一个纯 ASCII 的 {@code filename} 兜底——老客户端只认后者，虽然中文名会掉成占位名，
     * 但至少下得下来；反过来（只给中文名）在那些客户端上会变成乱码文件名。
     *
     * <p>名字里的日期用**用户传的参数**；没传时用导出当天。真实生效的区间以工作簿「概况」表里的
     * 「统计区间」为准（那才是服务端解析后的结果，含近 30 天的默认值）。
     */
    private static String attachmentName(LocalDate start, LocalDate end) {
        String suffix = start != null && end != null ? start + "_" + end : LocalDate.now().toString();
        String encoded = URLEncoder.encode("报修统计_" + suffix + ".xlsx", StandardCharsets.UTF_8)
                // URLEncoder 把空格编成 +，而 RFC 5987 只认 %20。现在文件名里没有空格，
                // 但这行留着——将来谁往文件名里加空格时，它保证 bug 不会以"文件名带个加号"的形式出现
                .replace("+", "%20");
        return "attachment; filename=\"statistics_" + suffix + ".xlsx\"; filename*=UTF-8''" + encoded;
    }
}