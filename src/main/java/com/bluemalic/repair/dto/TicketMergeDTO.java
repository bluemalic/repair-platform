package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 合并重复工单：把当前单并进 targetId 指向的那张单。 */
@Data
@Schema(description = "合并工单入参")
public class TicketMergeDTO {

    @Schema(description = "被合并进的目标工单ID（必须与本单同租户）", example = "1234567890")
    @NotNull(message = "目标工单不能为空")
    private Long targetId;

    @Schema(description = "合并说明（可选，会进流转日志）", example = "与 WX20261026001 是同一个漏水点")
    @Size(max = 255, message = "合并说明最长 255 字")
    private String remark;
}
