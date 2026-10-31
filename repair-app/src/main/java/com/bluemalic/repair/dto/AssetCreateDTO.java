package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 新增设备入参。
 *
 * <p>没有 status：新建一律"在用"。要停用/报废走修改接口——和楼栋同一条理由。
 * 类别与楼栋都是可选的：设备台账可以先建起来，再补归属。
 */
@Data
@Schema(description = "新增设备入参")
public class AssetCreateDTO {

    @Schema(description = "设备编号（租户内唯一，也是二维码内容）", example = "AC-3-201-01")
    @NotBlank(message = "设备编号不能为空")
    @Size(max = 64, message = "设备编号最长 64 位")
    private String assetNo;

    @Schema(description = "设备名称", example = "3号楼201空调")
    @NotBlank(message = "设备名称不能为空")
    @Size(max = 64, message = "设备名称最长 64 位")
    private String name;

    @Schema(description = "类别ID（ticket_category）")
    private Long categoryId;

    @Schema(description = "楼栋ID")
    private Long buildingId;

    @Schema(description = "房间/位置", example = "201")
    @Size(max = 32, message = "房间最长 32 位")
    private String room;

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
