package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 启用 / 停用租户。**停用会踢掉该租户全部在线用户**，所以它单独一个入口。 */
@Data
@Schema(description = "租户启停入参")
public class TenantStatusDTO {

    @Schema(description = "状态 1启用 0停用")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}
