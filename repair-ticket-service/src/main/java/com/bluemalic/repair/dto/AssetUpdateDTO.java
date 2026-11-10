package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 修改设备入参。PUT 是"提交最终状态"：status 必填；其余字段与新增一致。
 */
@Data
@Schema(description = "修改设备入参")
public class AssetUpdateDTO {

    @Schema(description = "设备编号（租户内唯一）", example = "AC-3-201-01")
    @NotBlank(message = "设备编号不能为空")
    @Size(max = 64, message = "设备编号最长 64 位")
    private String assetNo;

    @Schema(description = "设备名称", example = "3号楼201空调")
    @NotBlank(message = "设备名称不能为空")
    @Size(max = 64, message = "设备名称最长 64 位")
    private String name;

    @Schema(description = "类别ID")
    private Long categoryId;

    @Schema(description = "楼栋ID")
    private Long buildingId;

    @Schema(description = "房间/位置")
    @Size(max = 32, message = "房间最长 32 位")
    private String room;

    @Schema(description = "状态 1在用 2维修中 3停用/报废")
    @NotNull(message = "状态不能为空")
    @Min(value = 1, message = "状态只能是 1在用 / 2维修中 / 3停用报废")
    @Max(value = 3, message = "状态只能是 1在用 / 2维修中 / 3停用报废")
    private Integer status;

    @Schema(description = "采购日期")
    private LocalDate purchaseDate;

    @Schema(description = "保修截止日期")
    private LocalDate warrantyUntil;

    @Schema(description = "供应商")
    @Size(max = 64, message = "供应商最长 64 位")
    private String supplier;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注最长 255 位")
    private String remark;
}
