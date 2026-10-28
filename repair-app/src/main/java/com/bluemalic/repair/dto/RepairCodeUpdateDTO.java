package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改报修码入参（docs/03 §5.4：改房间号 / 启停 / 重新生成码）。
 *
 * <p>{@code regenerate} 是这批接口里唯一"动作型"的字段：码本身不在入参里（不许指定），
 * 但"重新生成一个"是管理端真实存在的需求（码泄露给无关的人、或贴纸磨损要换新）。
 */
@Data
@Schema(description = "修改报修码入参")
public class RepairCodeUpdateDTO {

    @Schema(description = "房间号，如 3-412", example = "3-412")
    @NotBlank(message = "房间号不能为空")
    @Size(max = 32, message = "房间号最长 32 位")
    private String room;

    @Schema(description = "状态 1启用 0停用；停用后扫码返回 20006（码无效或已失效）")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 1启用 / 0停用")
    @Max(value = 1, message = "状态只能是 1启用 / 0停用")
    private Integer status;

    @Schema(description = "是否重新生成码；不传按 false 处理。旧码立即失效")
    private Boolean regenerate;
}
