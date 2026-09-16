package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.WorkerBuildingsDTO;
import com.bluemalic.repair.dto.WorkerCreateDTO;
import com.bluemalic.repair.dto.WorkerUpdateDTO;
import com.bluemalic.repair.service.WorkerService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.WorkerVO;
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
 * 后勤端 - 维修工管理（docs/03 §5.4 基础数据）。
 *
 * <p>权限用类级注解统一管（照 {@code StatisticsController}）：这一组接口的权限码都是
 * {@code worker:manage}，逐个方法写一遍只是重复。
 *
 * <p>没有删除接口：停用即可，师傅记录要被历史工单和 {@code ticket_log} 引用。
 */
@Tag(name = "后勤管理端-维修工管理", description = "维修工账号、启停与负责楼栋（数据权限的物理依据）")
@SaCheckPermission("worker:manage")
@RestController
@RequestMapping("/api/admin/workers")
@RequiredArgsConstructor
public class AdminWorkerController {

    private final WorkerService workerService;

    @Operation(summary = "维修工列表", description = "分页；可按状态筛选、按工号或姓名模糊搜索。"
            + "返回里带负责楼栋，供派单选人与列表展示直接使用")
    @GetMapping
    public Result<PageResult<WorkerVO>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize,
            @Parameter(description = "状态筛选 1启用 0停用") @RequestParam(required = false) Integer status,
            @Parameter(description = "工号或姓名关键字") @RequestParam(required = false) String keyword) {
        return Result.ok(workerService.page(pageNum, pageSize, status, keyword));
    }

    @Operation(summary = "新增维修工",
            description = "工号即登录名（租户内唯一），初始密码由管理员设置并当面告知。"
                    + "用户类型固定为维修工，不接受传入")
    @PostMapping
    public Result<WorkerVO> create(@Valid @RequestBody WorkerCreateDTO dto) {
        return Result.ok(workerService.create(dto));
    }

    @Operation(summary = "修改维修工信息",
            description = "改姓名 / 手机号 / 启停；密码留空表示不修改（填了即管理员帮师傅重置）。"
                    + "停用会让该师傅已登录的 token 立即失效")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "维修工用户ID") @PathVariable long id,
                               @Valid @RequestBody WorkerUpdateDTO dto) {
        workerService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "设置负责楼栋",
            description = "全量替换：传什么就是他的全部负责楼栋，空数组表示不负责任何楼栋。"
                    + "这块数据是维修工数据可见范围的物理依据（ADR-002），改完立即生效")
    @PutMapping("/{id}/buildings")
    public Result<Void> setBuildings(@Parameter(description = "维修工用户ID") @PathVariable long id,
                                     @Valid @RequestBody WorkerBuildingsDTO dto) {
        workerService.setBuildings(id, dto);
        return Result.ok();
    }
}
