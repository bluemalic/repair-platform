package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** 设置某位维修工某天某班次的值班状态（按 worker + date + shiftType 唯一，重复提交即覆盖）。 */
@Data
@Schema(description = "设置排班入参")
public class WorkerShiftSaveDTO {

    @Schema(description = "维修工用户ID")
    @NotNull(message = "维修工不能为空")
    private Long workerId;

    @Schema(description = "日期", example = "2026-11-01")
    @NotNull(message = "日期不能为空")
    private LocalDate shiftDate;

    @Schema(description = "班次 1白班 2夜班")
    @NotNull(message = "班次不能为空")
    @Min(value = 1, message = "班次只能是 1白班 / 2夜班")
    @Max(value = 2, message = "班次只能是 1白班 / 2夜班")
    private Integer shiftType;

    @Schema(description = "值班状态 1在班 0请假/休息")
    @NotNull(message = "值班状态不能为空")
    @Min(value = 0, message = "值班状态只能是 1在班 / 0请假/休息")
    @Max(value = 1, message = "值班状态只能是 1在班 / 0请假/休息")
    private Integer status;

    @Schema(description = "备注，如「上午请假」")
    @Size(max = 255, message = "备注最长 255 位")
    private String remark;
}
