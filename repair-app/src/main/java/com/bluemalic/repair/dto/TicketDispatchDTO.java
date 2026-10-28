package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "派单入参")
public class TicketDispatchDTO {

    @Schema(description = "维修工用户ID")
    @NotNull(message = "维修工不能为空")
    private Long workerId;

    @Schema(description = "派单备注")
    @Size(max = 255, message = "派单备注最长 255 字")
    private String remark;
}
