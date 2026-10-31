package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.AssetCreateDTO;
import com.bluemalic.repair.dto.AssetUpdateDTO;
import com.bluemalic.repair.service.AssetService;
import com.bluemalic.repair.vo.AssetVO;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.TicketVO;
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

/**
 * 后勤端 - 设备台账（docs/01 §4.8）。权限用类级注解统一管。
 *
 * <p>列表分页：设备会随学校规模增长，和楼栋这种"几十条封顶"的字典不一样。
 */
@Tag(name = "后勤管理端-设备台账", description = "设备编号 / 位置 / 保修期 / 状态；工单可关联设备")
@SaCheckPermission("asset:manage")
@RestController
@RequestMapping("/api/admin/assets")
@RequiredArgsConstructor
public class AdminAssetController {

    private final AssetService assetService;

    @Operation(summary = "设备分页列表", description = "支持楼栋 / 类别 / 状态筛选与编号/名称关键字；按更新时间倒序")
    @GetMapping
    public Result<PageResult<AssetVO>> page(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize,
            @Parameter(description = "楼栋ID") @RequestParam(required = false) Long buildingId,
            @Parameter(description = "类别ID") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "状态 1在用 2维修中 3停用/报废") @RequestParam(required = false) Integer status,
            @Parameter(description = "编号或名称关键字") @RequestParam(required = false) String keyword) {
        return Result.ok(assetService.page(pageNum, pageSize, buildingId, categoryId, status, keyword));
    }

    @Operation(summary = "设备详情", description = "含类别名/楼栋名；设备不存在或不属于本租户返回 10006")
    @GetMapping("/{id}")
    public Result<AssetVO> detail(@Parameter(description = "设备ID") @PathVariable long id) {
        return Result.ok(assetService.detail(id));
    }

    @Operation(summary = "设备维修历史", description = "按 ticket.asset_id 分页，按提交时间倒序；工单可见范围仍由数据权限拦截器控制")
    @GetMapping("/{id}/tickets")
    public Result<PageResult<TicketVO>> tickets(
            @Parameter(description = "设备ID") @PathVariable long id,
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(assetService.tickets(id, pageNum, pageSize));
    }

    @Operation(summary = "新增设备", description = "设备编号租户内唯一；新建一律为在用")
    @PostMapping
    public Result<AssetVO> create(@Valid @RequestBody AssetCreateDTO dto) {
        return Result.ok(assetService.create(dto));
    }

    @Operation(summary = "修改设备", description = "PUT 提交最终状态；编号改重复返回 10001")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "设备ID") @PathVariable long id,
                               @Valid @RequestBody AssetUpdateDTO dto) {
        assetService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除设备", description = "只允许删除从未被工单引用的设备；有工单的返回 10001，请改为停用/报废")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "设备ID") @PathVariable long id) {
        assetService.delete(id);
        return Result.ok();
    }
}
