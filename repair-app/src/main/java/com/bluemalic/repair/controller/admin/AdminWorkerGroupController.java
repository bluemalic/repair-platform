package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.WorkerGroupCreateDTO;
import com.bluemalic.repair.dto.WorkerGroupUpdateDTO;
import com.bluemalic.repair.dto.WorkerGroupWorkersDTO;
import com.bluemalic.repair.service.WorkerGroupService;
import com.bluemalic.repair.vo.WorkerGroupVO;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后勤端 - 工种分组（阶段 3 · docs/01 §4.8）。
 *
 * <p>复用 {@code worker:manage}：工种与排班都是"维修工管理"的一部分，
 * 单独造权限码只会多一处要维护的授权。
 */
@Tag(name = "后勤管理端-工种分组", description = "派单评分的技能维度；不影响数据权限")
@SaCheckPermission("worker:manage")
@RestController
@RequestMapping("/api/admin/worker-groups")
@RequiredArgsConstructor
public class AdminWorkerGroupController {

    private final WorkerGroupService workerGroupService;

    @Operation(summary = "工种分组列表", description = "含组内维修工ID与人数；列表不分页（字典数据量小）")
    @GetMapping
    public Result<List<WorkerGroupVO>> list() {
        return Result.ok(workerGroupService.list());
    }

    @Operation(summary = "新增工种分组")
    @PostMapping
    public Result<WorkerGroupVO> create(@Valid @RequestBody WorkerGroupCreateDTO dto) {
        return Result.ok(workerGroupService.create(dto));
    }

    @Operation(summary = "修改工种分组", description = "PUT 提交最终状态；名称租户内唯一")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "分组ID") @PathVariable long id,
                               @Valid @RequestBody WorkerGroupUpdateDTO dto) {
        workerGroupService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "删除工种分组", description = "组内还有人、或仍被报修类别引用时拒绝")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "分组ID") @PathVariable long id) {
        workerGroupService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "设置组内维修工", description = "提交最终名单，空数组 = 清空；只接受本租户启用中的维修工")
    @PutMapping("/{id}/workers")
    public Result<Void> setWorkers(@Parameter(description = "分组ID") @PathVariable long id,
                                   @Valid @RequestBody WorkerGroupWorkersDTO dto) {
        workerGroupService.setWorkers(id, dto.getWorkerIds());
        return Result.ok();
    }
}
