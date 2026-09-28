package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 转派入参（`docs/01` §4.1）：把一张已派出去的单换个人做。
 *
 * <p>理由**必填**：这条动作会让原师傅手上的活突然消失，他得知道为什么
 * ——"临时有事"和"你做得不行"是两回事。理由进 `ticket_log` 并随通知发给他。
 */
@Data
@Schema(description = "转派入参")
public class TicketTransferDTO {

    @Schema(description = "新维修工ID")
    @NotNull(message = "请选择新维修工")
    private Long workerId;

    @Schema(description = "转派理由（会发给原师傅）")
    @NotBlank(message = "请填写转派理由")
    @Size(max = 255, message = "理由最长 255 字")
    private String reason;
}
