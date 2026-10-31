package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改工种分组入参（PUT = 提交最终状态）。 */
@Data
@Schema(description = "修改工种分组入参")
public class WorkerGroupUpdateDTO {

    @Schema(description = "工种名，如「水电工」")
    @NotBlank(message = "工种名称不能为空")
    @Size(max = 32, message = "工种名称最长 32 位")
    private String name;

    @Schema(description = "排序，小的在前")
    @NotNull(message = "排序不能为空")
    @Min(value = 0, message = "排序不能为负数")
    private Integer sort;

    @Schema(description = "状态 1启用 0停用")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 1启用 / 0停用")
    @Max(value = 1, message = "状态只能是 1启用 / 0停用")
    private Integer status;
}
