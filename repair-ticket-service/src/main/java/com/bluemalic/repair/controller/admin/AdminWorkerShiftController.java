package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.WorkerShiftSaveDTO;
import com.bluemalic.repair.service.WorkerShiftService;
import com.bluemalic.repair.vo.WorkerShiftVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 后勤端 - 排班（阶段 3 · docs/01 §4.8）。
 *
 * <p>只提供"某天有哪些明确排班"与"按人按天按班次覆盖"两个动作：
 * 排班表本身由学校自己维护，平台不做复杂的轮班规则（那会变成又一个排班系统）。
 */
@Tag(name = "后勤管理端-排班", description = "某天某班次是否在班；缺记录与明确休息是两件事")
@SaCheckPermission("worker:manage")
@RestController
@RequestMapping("/api/admin/worker-shifts")
@RequiredArgsConstructor
public class AdminWorkerShiftController {

    private final WorkerShiftService workerShiftService;

    @Operation(summary = "某天的排班", description = "只返回有明确记录的行；没有记录的维修工不在这里")
    @GetMapping
    public Result<List<WorkerShiftVO>> list(
            @Parameter(description = "日期，格式 yyyy-MM-dd", example = "2026-11-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Result.ok(workerShiftService.list(date));
    }

    @Operation(summary = "设置排班", description = "按 (维修工, 日期, 班次) 唯一，重复提交即覆盖")
    @PutMapping
    public Result<Void> save(@Valid @RequestBody WorkerShiftSaveDTO dto) {
        workerShiftService.save(dto);
        return Result.ok();
    }
}
