package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 设置分组内的维修工（提交最终名单，空数组 = 清空）。 */
@Data
@Schema(description = "设置分组维修工入参")
public class WorkerGroupWorkersDTO {

    @Schema(description = "维修工用户ID列表；必须是本租户启用中的维修工")
    @NotNull(message = "workerIds 不能为空（清空请传空数组）")
    private List<Long> workerIds;
}
