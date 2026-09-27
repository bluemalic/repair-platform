package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 验收不通过（打回重做）的入参，`docs/01` §4.1。
 *
 * <p>理由必填：不写理由，师傅只能猜哪里没做好——返工一轮的成本比写一行字高得多。
 * 它进 `ticket_log` 的备注（工单时间线上可见）并随通知发给维修工。
 */
@Data
@Schema(description = "验收不通过入参")
public class TicketReworkDTO {

    @Schema(description = "不通过的理由（哪里没修好）")
    @NotBlank(message = "请说明哪里没修好")
    @Size(max = 255, message = "理由最长 255 字")
    private String reason;
}
