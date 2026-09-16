package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增报修类别入参。
 *
 * <p>{@code defaultUrgency} 是"学生没选紧急度时的默认值"（如水电默认紧急），减少填写负担；
 * 不传按 1 普通处理。
 */
@Data
@Schema(description = "新增报修类别入参")
public class CategoryCreateDTO {

    @Schema(description = "类别名称，如「水电」", example = "门窗")
    @NotBlank(message = "类别名称不能为空")
    @Size(max = 32, message = "类别名称最长 32 位")
    private String name;

    @Schema(description = "默认紧急度 1普通 2紧急 3特急；不传按 1 处理")
    @Min(value = 1, message = "紧急度只能是 1普通 / 2紧急 / 3特急")
    @Max(value = 3, message = "紧急度只能是 1普通 / 2紧急 / 3特急")
    private Integer defaultUrgency;

    @Schema(description = "排序，小的在前；不传按 0 处理")
    @Min(value = 0, message = "排序不能为负数")
    private Integer sort;
}
