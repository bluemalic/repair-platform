package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.WorkerBuildingsDTO;
import com.bluemalic.repair.dto.WorkerSkillsDTO;
import com.bluemalic.repair.service.WorkerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维修工的**负责楼栋 / 擅长类别**（阶段 3.2 从 {@code AdminWorkerController} 拆出来）。
 *
 * <p><b>为什么单独一个类</b>：这两块数据是 {@code worker_building} / {@code worker_skill}，
 * 按表归属属于 ticket 域；账号本身的增删改归 identity。拆服务后它们要走不同的路由
 * （docs/03 §4.1 的 3.1 / 3.2 必须排在 {@code /api/admin/workers/**} 之前），
 * 放在同一个 Controller 里就没法按前缀分流。
 *
 * <p>路径前缀仍是 {@code /api/admin/workers}——对前端是零变化。
 */
@Tag(name = "后勤管理端-维修工范围", description = "负责楼栋与擅长类别（表归 ticket，账号归 identity）")
@SaCheckPermission("worker:manage")
@RestController
@RequestMapping("/api/admin/workers")
@RequiredArgsConstructor
public class AdminWorkerScopeController {

    private final WorkerService workerService;

    @Operation(summary = "设置负责楼栋",
            description = "全量替换：传什么就是他的全部负责楼栋，空数组表示不负责任何楼栋。"
                    + "这块数据是维修工数据可见范围的物理依据（ADR-002），改完立即生效")
    @PutMapping("/{id}/buildings")
    public Result<Void> setBuildings(@Parameter(description = "维修工用户ID") @PathVariable long id,
                                     @Valid @RequestBody WorkerBuildingsDTO dto) {
        workerService.setBuildings(id, dto);
        return Result.ok();
    }

    @Operation(summary = "设置擅长类别",
            description = "派单评分的技能维度（ADR-019）：按报修类别标注，提交最终状态（空数组=清空）。"
                    + "不影响可见范围——负责楼栋才影响数据权限")
    @PutMapping("/{id}/skills")
    public Result<Void> setSkills(@Parameter(description = "维修工用户ID") @PathVariable long id,
                                  @Valid @RequestBody WorkerSkillsDTO dto) {
        workerService.setSkills(id, dto);
        return Result.ok();
    }
}
