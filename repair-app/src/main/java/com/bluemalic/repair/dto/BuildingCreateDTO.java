package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增楼栋入参。
 *
 * <p>没有 status：新建的楼栋一律启用。想停用走修改接口——否则"新增一个停用的楼栋"这种
 * 自相矛盾的请求也得有个说法（照 {@code WorkerCreateDTO} 的同一条理由）。
 */
@Data
@Schema(description = "新增楼栋入参")
public class BuildingCreateDTO {

    @Schema(description = "楼栋名称，如「3号楼」", example = "6号楼")
    @NotBlank(message = "楼栋名称不能为空")
    @Size(max = 32, message = "楼栋名称最长 32 位")
    private String name;

    @Schema(description = "所属区域，如「东区」（可选）")
    @Size(max = 32, message = "所属区域最长 32 位")
    private String area;

    @Schema(description = "排序，小的在前；不传按 0 处理")
    @Min(value = 0, message = "排序不能为负数")
    private Integer sort;
}
