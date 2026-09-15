package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "提交报修入参")
public class TicketCreateDTO {

    @Schema(description = "报修码（扫码 / 手输），带上后自动定位楼栋与房间")
    private String repairCode;

    @Schema(description = "楼栋ID（没带报修码时必填）")
    private Long buildingId;

    @Schema(description = "房间号，如 3-412（没带报修码时必填）", example = "3-412")
    private String room;

    @Schema(description = "报修类别ID")
    @NotNull(message = "报修类别不能为空")
    private Long categoryId;

    @Schema(description = "问题描述")
    @NotBlank(message = "问题描述不能为空")
    @Size(max = 500, message = "问题描述最长 500 字")
    private String description;

    @Schema(description = "现场图片 URL 数组")
    @Size(max = 9, message = "现场图片最多 9 张")
    private List<String> images;

    @Schema(description = "紧急度 1普通 2紧急 3特急，不传则用类别默认值")
    @Min(value = 1, message = "紧急度只能是 1普通 / 2紧急 / 3特急")
    @Max(value = 3, message = "紧急度只能是 1普通 / 2紧急 / 3特急")
    private Integer urgency;
}
