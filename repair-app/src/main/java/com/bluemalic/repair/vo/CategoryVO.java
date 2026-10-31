package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 报修类别信息。
 */
@Data
@Schema(description = "报修类别信息")
public class CategoryVO implements Serializable {

    @Schema(description = "类别ID")
    private Long id;

    @Schema(description = "类别名称")
    private String name;

    @Schema(description = "默认紧急度 1普通 2紧急 3特急")
    private Integer defaultUrgency;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "默认工种分组ID（未设置时为 null）")
    private Long workerGroupId;

    @Schema(description = "默认工种分组名（未设置或分组已删除时为 null）")
    private String workerGroupName;

    @Schema(description = "状态 1启用 0停用")
    private Integer status;
}
