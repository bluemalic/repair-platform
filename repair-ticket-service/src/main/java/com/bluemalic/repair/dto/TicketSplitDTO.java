package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 拆单入参（`docs/01` §4.5）：从原单拆出一张新的待派单工单。
 *
 * <p>**楼栋 / 房间 / 学生 / 现场图片都继承原单**，不在这里传——它们描述的是"在哪、谁报的"，
 * 拆出来的那件事与原来的事在同一个位置、同一个学生。能改的只有"这件事本身是什么"：
 * 描述（必填，拆出来那件事得自己说清楚）、类别与紧急度（默认继承原单，可改——
 * "灯坏了"与"水龙头漏水"本来就不是一类）。
 */
@Data
@Schema(description = "拆单入参")
public class TicketSplitDTO {

    @Schema(description = "拆出来那件事的描述（必填）")
    @NotBlank(message = "请写清拆出来的是什么问题")
    @Size(max = 500, message = "问题描述最长 500 字")
    private String description;

    @Schema(description = "报修类别ID，不传则继承原单")
    private Long categoryId;

    @Schema(description = "紧急度 1普通 2紧急 3特急，不传则继承原单")
    @Min(value = 1, message = "紧急度只能是 1普通 / 2紧急 / 3特急")
    @Max(value = 3, message = "紧急度只能是 1普通 / 2紧急 / 3特急")
    private Integer urgency;
}
