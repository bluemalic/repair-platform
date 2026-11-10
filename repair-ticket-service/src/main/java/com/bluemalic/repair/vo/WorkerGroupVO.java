package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 工种分组（阶段 3 · docs/01 §4.8）。 */
@Data
@Schema(description = "工种分组")
public class WorkerGroupVO {

    private Long id;

    private String name;

    private Integer sort;

    /** 1启用 0停用 */
    private Integer status;

    @Schema(description = "组内维修工ID（编辑弹窗回显用）")
    private List<Long> workerIds;

    @Schema(description = "组内人数")
    private Integer workerCount;
}
