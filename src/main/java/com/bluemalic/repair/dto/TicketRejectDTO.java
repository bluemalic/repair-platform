package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "驳回入参")
public class TicketRejectDTO {

    @Schema(description = "驳回理由")
    @NotBlank(message = "驳回理由不能为空")
    @Size(max = 255, message = "驳回理由最长 255 字")
    private String reason;
}
