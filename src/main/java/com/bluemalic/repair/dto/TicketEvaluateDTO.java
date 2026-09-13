package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "验收评价入参")
public class TicketEvaluateDTO {

    @Schema(description = "评分 1-5")
    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低 1 分")
    @Max(value = 5, message = "评分最高 5 分")
    private Integer score;

    @Schema(description = "评价内容")
    @Size(max = 500, message = "评价内容最长 500 字")
    private String content;
}
