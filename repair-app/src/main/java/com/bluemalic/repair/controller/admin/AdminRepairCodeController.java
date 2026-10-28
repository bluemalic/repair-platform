package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.RepairCodeCreateDTO;
import com.bluemalic.repair.dto.RepairCodeUpdateDTO;
import com.bluemalic.repair.service.RepairCodeService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.RepairCodeDetailVO;
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
 * 后勤端 - 报修码管理（docs/03 §5.4 基础数据）。
 *
 * <p>码是"位置码"（ADR-004）：一条记录 = 一个房间门口的一把码，学生扫码报修与维修工扫码到场共用。
 * 因此**码由服务端随机生成**、入参里没有 code；也**没有删除接口**，不再使用的码停用即可。
 * 房间填错了改房间号，码要换用「重新生成」。
 *
 * <p>二维码不需要后端接口：内容就是 {@code code} 本身，前端用 qrcode 库渲染后打印（docs/03 §7.1）。
 */
@Tag(name = "后勤管理端-报修码管理", description = "房间位置码：生成、改房间、启停与重新生成")
@SaCheckPermission("repaircode:manage")
@RestController
@RequestMapping("/api/admin/repair-codes")
@RequiredArgsConstructor
public class AdminRepairCodeController {

    private final RepairCodeService repairCodeService;

    @Operation(summary = "报修码列表", description = "分页，可按楼栋 / 状态筛选；新生成的排在前面。"
            + "返回里带楼栋名，前端直接显示「1号楼 1-101」")
    @GetMapping
    public Result<PageResult<RepairCodeDetailVO>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize,
            @Parameter(description = "楼栋筛选") @RequestParam(required = false) Long buildingId,
            @Parameter(description = "状态筛选 1启用 0停用") @RequestParam(required = false) Integer status) {
        return Result.ok(repairCodeService.page(pageNum, pageSize, buildingId, status));
    }

    @Operation(summary = "生成报修码",
            description = "为指定楼栋 + 房间生成随机 6 位数字码（服务端随机，不接受指定）。"
                    + "同一房间不允许存在第二条码（一房一码）")
    @PostMapping
    public Result<RepairCodeDetailVO> create(@Valid @RequestBody RepairCodeCreateDTO dto) {
        return Result.ok(repairCodeService.create(dto));
    }

    @Operation(summary = "修改报修码",
            description = "改房间号 / 启停（PUT = 提交最终状态）。`regenerate=true` 时**另外重新生成一个码**，"
                    + "旧码立即失效——码泄露给无关的人、或贴纸磨损时用")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "报修码记录ID（不是码本身）") @PathVariable long id,
                               @Valid @RequestBody RepairCodeUpdateDTO dto) {
        repairCodeService.update(id, dto);
        return Result.ok();
    }
}
