package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改报修类别入参（PUT = 提交最终状态，字段都必填）。
 */
@Data
@Schema(description = "修改报修类别入参")
public class CategoryUpdateDTO {

    @Schema(description = "类别名称，如「水电」", example = "门窗")
    @NotBlank(message = "类别名称不能为空")
    @Size(max = 32, message = "类别名称最长 32 位")
    private String name;

    @Schema(description = "默认紧急度 1普通 2紧急 3特急")
    @NotNull(message = "默认紧急度不能为空")
    @Min(value = 1, message = "紧急度只能是 1普通 / 2紧急 / 3特急")
    @Max(value = 3, message = "紧急度只能是 1普通 / 2紧急 / 3特急")
    private Integer defaultUrgency;

    @Schema(description = "排序，小的在前")
    @NotNull(message = "排序不能为空")
    @Min(value = 0, message = "排序不能为负数")
    private Integer sort;

    @Schema(description = "状态 1启用 0停用；停用的类别不能用于新报修")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 1启用 / 0停用")
    @Max(value = 1, message = "状态只能是 1启用 / 0停用")
    private Integer status;
}
