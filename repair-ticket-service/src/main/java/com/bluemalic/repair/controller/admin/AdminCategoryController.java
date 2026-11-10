package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.CategoryCreateDTO;
import com.bluemalic.repair.dto.CategoryUpdateDTO;
import com.bluemalic.repair.service.CategoryService;
import com.bluemalic.repair.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后勤端 - 报修类别管理（docs/03 §5.4 基础数据）。
 *
 * <p>类别上的 {@code defaultUrgency} 是"学生没选紧急度时的默认值"（如水电默认紧急）。
 */
@Tag(name = "后勤管理端-报修类别管理", description = "租户内的类别字典，含默认紧急度")
@SaCheckPermission("category:manage")
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "类别列表", description = "按 sort 升序返回全部类别；可按状态筛选，不传返回全部。不分页")
    @GetMapping
    public Result<List<CategoryVO>> list(
            @Parameter(description = "状态筛选 1启用 0停用") @RequestParam(required = false) Integer status) {
        return Result.ok(categoryService.list(status));
    }

    @Operation(summary = "新增类别", description = "新建的类别一律为启用状态；名称在租户内不允许重复")
    @PostMapping
    public Result<CategoryVO> create(@Valid @RequestBody CategoryCreateDTO dto) {
        return Result.ok(categoryService.create(dto));
    }

    @Operation(summary = "修改类别",
            description = "改名称 / 默认紧急度 / 排序 / 启停（PUT = 提交最终状态，字段都必填）。"
                    + "停用后不能用于新报修；历史工单照常显示名称")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "类别ID") @PathVariable long id,
                               @Valid @RequestBody CategoryUpdateDTO dto) {
        categoryService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除类别",
            description = "逻辑删除，且**只允许删除从未被工单引用的类别**；已被引用返回 10001 并说明原因。"
                    + "在用类别的下线方式是「停用」")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "类别ID") @PathVariable long id) {
        categoryService.delete(id);
        return Result.ok();
    }
}
