package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 楼栋信息。字典数据，字段就是前端表单需要的那些——{@code tenantId} / {@code deleted}
 * 这类内部字段不外露（AGENTS §5.4：禁止把 entity 直接当接口出入参）。
 */
@Data
@Schema(description = "楼栋信息")
public class BuildingVO {

    @Schema(description = "楼栋ID")
    private Long id;

    @Schema(description = "楼栋名称")
    private String name;

    @Schema(description = "所属区域")
    private String area;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "状态 1启用 0停用")
    private Integer status;
}
