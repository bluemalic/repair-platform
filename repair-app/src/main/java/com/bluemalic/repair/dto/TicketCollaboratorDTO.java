package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 加协作者的入参（`docs/01` §4.5）。 */
@Data
@Schema(description = "加协作者")
public class TicketCollaboratorDTO {

    @Schema(description = "协作维修工ID（本租户、启用中）")
    @NotNull(message = "请选择维修工")
    private Long workerId;
}
