package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 知识库条目的新增 / 修改入参（同一个 DTO：两者字段完全一样，没有"只有新增能填"的字段）。
 *
 * <p>{@code categoryId} 允许留空 = 通用条目（不属于任何类别，任何类别的工单都可能命中）。
 */
@Data
public class KnowledgeEntryDTO {

    @Schema(description = "标题，如「水管接头漏水」")
    @NotBlank(message = "标题不能为空")
    @Size(max = 64, message = "标题不能超过 64 字")
    private String title;

    @Schema(description = "现象 / 适用场景——检索主字段，写用户会说的那种话")
    @NotBlank(message = "现象不能为空")
    @Size(max = 255, message = "现象不能超过 255 字")
    private String symptom;

    @Schema(description = "处理步骤，一行一步")
    @NotBlank(message = "处理步骤不能为空")
    private String solution;

    @Schema(description = "挂到报修类别；留空 = 通用条目")
    private Long categoryId;

    @Schema(description = "关键词（空格分隔）。用来补同义词，如「漏水 渗水 滴水」")
    @Size(max = 128, message = "关键词不能超过 128 字")
    private String tags;

    @Schema(description = "1 启用 / 0 停用")
    private Integer status;
}
