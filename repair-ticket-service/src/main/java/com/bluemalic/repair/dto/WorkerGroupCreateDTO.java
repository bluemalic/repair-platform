package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新增工种分组入参。新建一律启用，停用走修改接口。 */
@Data
@Schema(description = "新增工种分组入参")
public class WorkerGroupCreateDTO {

    @Schema(description = "工种名，如「水电工」", example = "空调工")
    @NotBlank(message = "工种名称不能为空")
    @Size(max = 32, message = "工种名称最长 32 位")
    private String name;

    @Schema(description = "排序，小的在前；不传按 0 处理")
    @Min(value = 0, message = "排序不能为负数")
    private Integer sort;
}
