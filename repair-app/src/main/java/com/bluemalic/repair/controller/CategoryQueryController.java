package com.bluemalic.repair.controller;

import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.service.CategoryService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 报修类别的**只读**接口，各端共用（docs/03 §5.1）。
 *
 * <p>为什么单独开一个而不复用 `/api/admin/categories`：那个接口是后勤专属权限（`category:manage`），
 * 而学生提交报修时必须选类别——权限不够会直接 403，报修在移动端就走不通。
 * 这里只暴露"启用中的类别"，不含停用的、也不能改（写操作仍然只在管理端）。
 */
@Tag(name = "报修类别", description = "各端共用的类别查询（只读）")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryQueryController {

    private final CategoryService categoryService;
    private final CurrentTenantService currentTenantService;

    @Operation(summary = "启用中的报修类别", description = "不分页、按 sort 升序；供学生报修表单选择")
    @GetMapping
    public Result<List<CategoryVO>> listEnabled() {
        // 只返启用中的（语义固定的那条查询），并在实现里按租户缓存
        return Result.ok(categoryService.listEnabled(currentTenantService.requireTenantId()));
    }
}
