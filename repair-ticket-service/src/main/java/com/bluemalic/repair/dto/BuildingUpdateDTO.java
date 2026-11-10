package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改楼栋入参。
 *
 * <p>PUT 是"提交最终状态"：sort 与 status 都必填，不传视为请求不完整（10001）。
 * 差异只在**新增**接口上——那里缺省值有明确含义（sort 0 / 启用），这里没有。
 */
@Data
@Schema(description = "修改楼栋入参")
public class BuildingUpdateDTO {

    @Schema(description = "楼栋名称，如「3号楼」", example = "6号楼")
    @NotBlank(message = "楼栋名称不能为空")
    @Size(max = 32, message = "楼栋名称最长 32 位")
    private String name;

    @Schema(description = "所属区域，如「东区」（可选）")
    @Size(max = 32, message = "所属区域最长 32 位")
    private String area;

    @Schema(description = "排序，小的在前")
    @NotNull(message = "排序不能为空")
    @Min(value = 0, message = "排序不能为负数")
    private Integer sort;

    @Schema(description = "状态 1启用 0停用；停用的楼栋不能用于新报修，也不出现在管理端的可选列表里")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 1启用 / 0停用")
    @Max(value = 1, message = "状态只能是 1启用 / 0停用")
    private Integer status;
}
