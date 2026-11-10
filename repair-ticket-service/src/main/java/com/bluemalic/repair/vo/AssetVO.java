package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 设备信息。{@code tenantId}/{@code deleted} 不外露；类别/楼栋名由 Service 补上，
 * 前端列表不用再拿 id 去换名称。
 */
@Data
@Schema(description = "设备信息")
public class AssetVO {

    @Schema(description = "设备ID")
    private Long id;

    @Schema(description = "设备编号（二维码内容）")
    private String assetNo;

    @Schema(description = "设备名称")
    private String name;

    @Schema(description = "类别ID")
    private Long categoryId;

    @Schema(description = "类别名称")
    private String categoryName;

    @Schema(description = "楼栋ID")
    private Long buildingId;

    @Schema(description = "楼栋名称")
    private String buildingName;

    @Schema(description = "房间/位置")
    private String room;

    @Schema(description = "状态 1在用 2维修中 3停用/报废")
    private Integer status;

    @Schema(description = "采购日期")
    private LocalDate purchaseDate;

    @Schema(description = "保修截止日期")
    private LocalDate warrantyUntil;

    @Schema(description = "供应商")
    private String supplier;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
