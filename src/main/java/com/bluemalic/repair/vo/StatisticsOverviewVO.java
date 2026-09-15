package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 核心指标卡。平均值与比率字段为 null 表示"样本不足"（如区间内没有到场记录），
 * 前端应对 null 显示"-"而不是 0——0 和"没有数据"是两件事。
 */
@Data
@Schema(description = "统计总览（核心指标卡）")
public class StatisticsOverviewVO {

    @Schema(description = "工单总量")
    private Integer total;

    @Schema(description = "平均响应时长（分钟）= 到场 − 派单，仅统计有到场记录的工单")
    private Double avgResponseMinutes;

    @Schema(description = "平均处理时长（分钟）= 完工 − 到场，仅统计有完工记录的工单")
    private Double avgHandleMinutes;

    @Schema(description = "触发过超时的工单数（24h 未接单 / 48h 未处理 / 验收超时自动关）")
    private Integer timeoutCount;

    @Schema(description = "超时率（百分比 0-100，两位小数）= 超时工单数 / 工单总量")
    private Double timeoutRate;

    @Schema(description = "平均满意度（1-5），区间内无评价时为 null")
    private Double avgScore;
}
