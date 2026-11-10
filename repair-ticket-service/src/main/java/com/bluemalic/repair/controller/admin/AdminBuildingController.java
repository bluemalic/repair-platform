package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.BuildingCreateDTO;
import com.bluemalic.repair.dto.BuildingUpdateDTO;
import com.bluemalic.repair.service.BuildingService;
import com.bluemalic.repair.vo.BuildingVO;
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
 * 后勤端 - 楼栋管理（docs/03 §5.4 基础数据）。权限用类级注解统一管。
 *
 * <p>列表**不分页**：字典数据量小，前端的下拉框需要一次拿全（详见 {@code BuildingService}）。
 */
@Tag(name = "后勤管理端-楼栋管理", description = "租户内的楼栋字典：报修表单与统计按它聚合")
@SaCheckPermission("building:manage")
@RestController
@RequestMapping("/api/admin/buildings")
@RequiredArgsConstructor
public class AdminBuildingController {

    private final BuildingService buildingService;

    @Operation(summary = "楼栋列表", description = "按 sort 升序返回全部楼栋；可按状态筛选，不传返回全部"
            + "（管理端要能看到已停用的并重新启用）。不分页")
    @GetMapping
    public Result<List<BuildingVO>> list(
            @Parameter(description = "状态筛选 1启用 0停用") @RequestParam(required = false) Integer status) {
        return Result.ok(buildingService.list(status));
    }

    @Operation(summary = "新增楼栋", description = "新建的楼栋一律为启用状态；名称在租户内不允许重复")
    @PostMapping
    public Result<BuildingVO> create(@Valid @RequestBody BuildingCreateDTO dto) {
        return Result.ok(buildingService.create(dto));
    }

    @Operation(summary = "修改楼栋",
            description = "改名称 / 区域 / 排序 / 启停（PUT = 提交最终状态，字段都必填）。"
                    + "停用后该楼栋不能用于新报修，但历史工单照常显示名称")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "楼栋ID") @PathVariable long id,
                               @Valid @RequestBody BuildingUpdateDTO dto) {
        buildingService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除楼栋",
            description = "逻辑删除，且**只允许删除从未被引用的楼栋**：已有工单 / 有维修工负责 / 还有报修码，"
                    + "都会返回 10001 并说明原因。在用楼栋的下线方式是「停用」")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "楼栋ID") @PathVariable long id) {
        buildingService.delete(id);
        return Result.ok();
    }
}
