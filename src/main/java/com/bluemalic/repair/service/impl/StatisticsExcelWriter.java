package com.bluemalic.repair.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import com.bluemalic.repair.vo.StatisticsDistributionVO;
import com.bluemalic.repair.vo.StatisticsOverviewVO;
import com.bluemalic.repair.vo.StatisticsTrendVO;
import com.bluemalic.repair.vo.StatisticsWorkerWorkloadVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 把统计报表写成一张 xlsx：**四张工作表 = 看板的四块内容**（概况 / 报修量趋势 / 分布统计 / 师傅工作量）。
 *
 * <p>它放在 {@code service/impl} 是因为它只服务于 {@link StatisticsServiceImpl}：**不查库、不算口径**，
 * 拿到的就是四个接口方法的结果，只负责摆成表。这条纪律是它存在的全部意义——一旦这里开始自己算数，
 * "Excel 里的数与页面上的数不一致"就只是时间问题。
 *
 * <p><b>为什么先写进内存、再交给 Controller 发出去，而不是直接写响应流</b>：报表是聚合结果，
 * 四张表加起来最多几百行（趋势表最长 366 行）、几十 KB。直写响应流省下的那点内存，
 * 换来两个实实在在的代价：① 时间范围校验失败时响应头可能已经发出去了，浏览器会把错误 JSON
 * 当成 .xlsx 存下来，现象是"下载了一个打不开的文件"；② 没有长度，浏览器没有下载进度。
 *
 * <p><b>这条边界要记住</b>：将来真要做"工单明细导出"（几万行），必须改成直写响应流——那时内存扛不住。
 * 改的时候"第一字节之前完成全部校验"这条规则不能丢，只是得换成别的手段保证（例如先把数据取齐再开始写）。
 *
 * <p><b>数值列与文本列区别对待</b>：概况表是给人读的，一律写文本，无样本写成 {@value #NO_SAMPLE}
 * （与页面显示一致）；数据表的数值列保持数值类型（Excel 里能排序、能求和），无样本留空单元格——
 * 空 = 区间内没有可统计的记录。
 */
@Component
public class StatisticsExcelWriter {

    private static final String SHEET_SUMMARY = "概况";
    private static final String SHEET_TREND = "报修量趋势";
    private static final String SHEET_DISTRIBUTION = "分布统计";
    private static final String SHEET_WORKLOAD = "师傅工作量";

    /** 无样本时的占位。**与前端页面一致**：0 和"没有数据"是两件事。 */
    private static final String NO_SAMPLE = "-";

    /** 一份报表：四个数据集 + **已解析的**统计区间（默认近 30 天只有 Service 知道，所以由它传进来）。 */
    public record Report(LocalDate start,
                         LocalDate end,
                         StatisticsOverviewVO overview,
                         List<StatisticsTrendVO> trend,
                         List<StatisticsDistributionVO> categories,
                         List<StatisticsDistributionVO> buildings,
                         List<StatisticsDistributionVO> urgencies,
                         List<StatisticsWorkerWorkloadVO> workload) {
    }

    byte[] toBytes(Report report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 一个 ExcelWriter 写多张表 = 一个工作簿多个工作表；每张表各带自己的表头类型
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            writer.write(summaryRows(report), sheet(0, SHEET_SUMMARY, SummaryRow.class));
            writer.write(trendRows(report.trend()), sheet(1, SHEET_TREND, TrendRow.class));
            writer.write(distributionRows(report), sheet(2, SHEET_DISTRIBUTION, DistributionRow.class));
            writer.write(workloadRows(report.workload()), sheet(3, SHEET_WORKLOAD, WorkloadRow.class));
        }
        return out.toByteArray();
    }

    /**
     * 列宽按内容自适应：中文列（楼栋名、师傅名、口径说明）按字符数固定宽度不是宽了就是窄了，
     * 让库自己量更省事。它只影响观感，不影响取数。
     */
    private WriteSheet sheet(int sheetNo, String sheetName, Class<?> head) {
        return EasyExcel.writerSheet(sheetNo, sheetName)
                .head(head)
                .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                .build();
    }

    /** 概况：指标 / 数值 / 口径。"口径"这一列是从 docs/03 §5.4 的口径表抄过来的——
     *  报表发出去之后没有人能问它问题，口径必须跟着文件走。 */
    private List<SummaryRow> summaryRows(Report report) {
        StatisticsOverviewVO overview = report.overview();
        List<SummaryRow> rows = new ArrayList<>();
        rows.add(new SummaryRow("统计区间", report.start() + " ~ " + report.end(),
                "含首尾两天；不指定时为近 30 天"));
        rows.add(new SummaryRow("工单总量", String.valueOf(overview.getTotal()),
                "区间内提交的工单"));
        rows.add(new SummaryRow("超时工单数", String.valueOf(overview.getTimeoutCount()),
                "出现过接单超时 / 处理超时 / 验收超时自动关闭的工单，以系统实际触发的超时动作为准"));
        rows.add(new SummaryRow("超时率（%）", text(overview.getTimeoutRate()),
                "超时工单数 ÷ 工单总量 × 100"));
        rows.add(new SummaryRow("平均响应时长（分钟）", text(overview.getAvgResponseMinutes()),
                "到场 − 派单，只统计有到场记录的工单；" + NO_SAMPLE + " = 无样本"));
        rows.add(new SummaryRow("平均处理时长（分钟）", text(overview.getAvgHandleMinutes()),
                "完工 − 到场，只统计有完工记录的工单；" + NO_SAMPLE + " = 无样本"));
        rows.add(new SummaryRow("平均满意度（1-5）", text(overview.getAvgScore()),
                "只统计通过验收并打过分的工单；" + NO_SAMPLE + " = 区间内没有评价"));
        return rows;
    }

    private List<TrendRow> trendRows(List<StatisticsTrendVO> points) {
        return points.stream().map(point -> new TrendRow(point.getDate(), point.getCount())).toList();
    }

    /** 三个维度平铺在一张表里，用「维度」列区分：不合并单元格，透视和筛选都不用先处理一遍。 */
    private List<DistributionRow> distributionRows(Report report) {
        List<DistributionRow> rows = new ArrayList<>();
        addDistribution(rows, "类别", report.categories());
        addDistribution(rows, "楼栋", report.buildings());
        addDistribution(rows, "紧急度", report.urgencies());
        return rows;
    }

    private void addDistribution(List<DistributionRow> rows, String dimension,
                                 List<StatisticsDistributionVO> items) {
        items.forEach(item -> rows.add(new DistributionRow(dimension, item.getName(), item.getCount())));
    }

    private List<WorkloadRow> workloadRows(List<StatisticsWorkerWorkloadVO> workload) {
        return workload.stream()
                .map(row -> new WorkloadRow(row.getWorkerName(), row.getFinishedCount(),
                        row.getAvgHandleMinutes(), row.getProcessTimeoutCount(), row.getOnTimeRate()))
                .toList();
    }

    /** 数值转成人读的文本：50.0 → 50，12.50 → 12.5（两位小数是 Service 算的，这里只去掉多余的零）。 */
    private static String text(Double value) {
        return value == null ? NO_SAMPLE : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // ==================== 各表的行结构 ====================
    // 行结构是导出的内部实现，不是接口契约，所以放在这里与"怎么填"待在一起。
    // 注意：**要用类不用 record**——EasyExcel 通过 JavaBean 的 getter 取值，record 的访问器不带 get 前缀，取不到。

    @Data
    @AllArgsConstructor
    public static class SummaryRow {

        @ExcelProperty("指标")
        private String metric;

        @ExcelProperty("数值")
        private String value;

        @ExcelProperty("口径")
        private String definition;
    }

    @Data
    @AllArgsConstructor
    public static class TrendRow {

        @ExcelProperty("日期")
        private String date;

        @ExcelProperty("报修量")
        private Integer count;
    }

    @Data
    @AllArgsConstructor
    public static class DistributionRow {

        @ExcelProperty("维度")
        private String dimension;

        @ExcelProperty("名称")
        private String name;

        @ExcelProperty("工单数")
        private Integer count;
    }

    @Data
    @AllArgsConstructor
    public static class WorkloadRow {

        @ExcelProperty("师傅")
        private String workerName;

        @ExcelProperty("完工数")
        private Integer finishedCount;

        @ExcelProperty("平均处理时长（分钟）")
        private Double avgHandleMinutes;

        @ExcelProperty("处理超时次数")
        private Integer processTimeoutCount;

        @ExcelProperty("按时完成率（%）")
        private Double onTimeRate;
    }
}
