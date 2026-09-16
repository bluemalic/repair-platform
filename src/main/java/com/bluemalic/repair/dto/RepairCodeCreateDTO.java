package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 生成报修码入参：指定楼栋 + 房间，**码由服务端随机生成**（ADR-004）。
 *
 * <p>没有 code 字段，是故意的：码必须随机、且不能由调用方指定——一旦能指定，
 * 顺序码 / 好记码就会重新出现，防枚举（ADR-009）的前提就没了。
 */
@Data
@Schema(description = "生成报修码入参")
public class RepairCodeCreateDTO {

    @Schema(description = "楼栋ID")
    @NotNull(message = "楼栋不能为空")
    private Long buildingId;

    @Schema(description = "房间号，如 3-412", example = "3-412")
    @NotBlank(message = "房间号不能为空")
    @Size(max = 32, message = "房间号最长 32 位")
    private String room;
}
