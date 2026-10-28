package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 设置维修工擅长类别（阶段 3 · ADR-019）。
 *
 * <p>语义与 {@code WorkerBuildingsDTO} 一致：**提交最终状态**，空数组表示清空。
 * 它是派单评分的"技能"输入——按报修类别（`ticket_category`）标注，不是自由文本。
 */
@Data
public class WorkerSkillsDTO {

    @Schema(description = "擅长类别ID列表；清空请传空数组")
    @NotNull(message = "类别列表不能为空，清空请传空数组")
    private List<Long> categoryIds;
}
