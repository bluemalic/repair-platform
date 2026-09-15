package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 统计看板查询条件。字段都可选：不传时间范围默认"近 30 天"（含今天），
 * 由 Service 统一补默认值——避免每个调用方各写一套默认口径。
 */
@Data
@Schema(description = "统计查询条件")
public class StatisticsQueryDTO {

    @Schema(description = "起始日期（含），不传 = 近 30 天前")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate start;

    @Schema(description = "结束日期（含），不传 = 今天")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate end;

    @Schema(description = "趋势粒度：day（按天）/ week（按周，周一为起点）", defaultValue = "day")
    private String granularity = "day";

    @Schema(description = "分布维度：category（类别）/ building（楼栋）/ urgency（紧急度）")
    private String dimension;
}