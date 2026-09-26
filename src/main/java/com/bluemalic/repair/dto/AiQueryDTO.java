package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 问数入参。
 *
 * <p>{@code question} 有长度上限，理由是**成本**：这句话会进提示词、每次问数调用模型两次，
 * 长度直接变成 token 账单。200 字够描述任何"问数据"的问题了，再长的多半是把表格粘进来了。
 */
@Data
@Schema(description = "AI 问数入参")
public class AiQueryDTO {

    @Schema(description = "自然语言问题", example = "上月3号楼报修最多的是什么")
    @NotBlank(message = "问题不能为空")
    @Size(max = 200, message = "问题最长 200 字")
    private String question;
}
