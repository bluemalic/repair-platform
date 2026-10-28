package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.KnowledgeEntryDTO;
import com.bluemalic.repair.service.KnowledgeService;
import com.bluemalic.repair.vo.KnowledgeEntryVO;
import com.bluemalic.repair.vo.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后勤端 - 维修知识库维护（docs/03 §5.4）。
 *
 * <p>这些条目是师傅端「排查建议」的原料：检索命中什么，建议就只能依据什么（ADR-018）。
 * 所以维护质量直接决定建议质量——表单上的两个提示不是装饰：
 * {@code symptom} 要写"用户会说的那种话"，{@code tags} 要补同义词。
 *
 * <p>**没有删除接口**：条目会被历史建议引用，删除会断掉"当时凭什么给这条建议"的线索，
 * 下线一律用停用。
 */
@Tag(name = "后勤管理端-维修知识库", description = "本学校的「现象 → 处理步骤」条目，师傅端建议的原料")
@SaCheckPermission("knowledge:manage")
@RestController
@RequestMapping("/api/admin/knowledge")
@RequiredArgsConstructor
public class AdminKnowledgeController {

    private final KnowledgeService knowledgeService;

    @Operation(summary = "知识库列表", description = "分页；可按关键词（标题 / 现象）、类别、状态筛选")
    @GetMapping
    public Result<PageResult<KnowledgeEntryVO>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize,
            @Parameter(description = "关键词（匹配标题或现象）") @RequestParam(required = false) String keyword,
            @Parameter(description = "类别筛选") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "状态筛选：1 启用 / 0 停用") @RequestParam(required = false) Integer status) {
        return Result.ok(knowledgeService.page(pageNum, pageSize, keyword, categoryId, status));
    }

    @Operation(summary = "新增条目",
            description = "symptom 写用户会说的话（检索主字段）；tags 里补同义词，如「漏水 渗水 滴水」")
    @PostMapping
    public Result<KnowledgeEntryVO> create(@Valid @RequestBody KnowledgeEntryDTO dto) {
        return Result.ok(knowledgeService.create(dto));
    }

    @Operation(summary = "修改条目", description = "字段全量覆盖；类别留空即改为通用条目")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "条目ID") @PathVariable long id,
                              @Valid @RequestBody KnowledgeEntryDTO dto) {
        knowledgeService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "启用 / 停用",
            description = "**没有删除**：条目会被历史建议引用，下线请用停用")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@Parameter(description = "条目ID") @PathVariable long id,
                                     @Parameter(description = "1 启用 / 0 停用") @RequestParam Integer status) {
        knowledgeService.updateStatus(id, status);
        return Result.ok();
    }
}
