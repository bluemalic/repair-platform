package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 报修类别信息。
 */
@Data
@Schema(description = "报修类别信息")
public class CategoryVO {

    @Schema(description = "类别ID")
    private Long id;

    @Schema(description = "类别名称")
    private String name;

    @Schema(description = "默认紧急度 1普通 2紧急 3特急")
    private Integer defaultUrgency;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "状态 1启用 0停用")
    private Integer status;
}
