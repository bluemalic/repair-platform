package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 需要说明原因的处理动作（待料 / 无法维修）。理由会进 ticket_log，时间线上看得见。 */
@Data
@Schema(description = "处理动作原因")
public class TicketReasonDTO {

    @Schema(description = "原因，必填", example = "需要更换的密封圈仓库没货")
    @NotBlank(message = "原因不能为空")
    @Size(max = 255, message = "原因最长 255 字")
    private String reason;
}
