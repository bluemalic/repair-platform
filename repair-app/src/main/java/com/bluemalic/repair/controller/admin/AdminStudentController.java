package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.StudentCreateDTO;
import com.bluemalic.repair.dto.StudentImportDTO;
import com.bluemalic.repair.dto.StudentPasswordDTO;
import com.bluemalic.repair.dto.StudentUpdateDTO;
import com.bluemalic.repair.service.StudentService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.StudentImportVO;
import com.bluemalic.repair.vo.StudentVO;
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
 * 后勤端 - 学生账号管理。
 *
 * <p>权限用类级注解统一管（照 {@code AdminWorkerController}）：这一组接口的权限码都是
 * {@code student:manage}。
 *
 * <p>没有删除接口：停用即可——学生记录要被历史工单和评价引用，删了就说不清"这单是谁报的"。
 * 而且 {@code sys_user} 上还有 {@code uk_tenant_username} 唯一索引，逻辑删除不会释放登录名，
 * 删掉之后同一个学号再也建不回来。
 */
@Tag(name = "后勤管理端-学生账号", description = "学生账号的建号 / 启停 / 重置口令 / 批量导入")
@SaCheckPermission("student:manage")
@RestController
@RequestMapping("/api/admin/students")
@RequiredArgsConstructor
public class AdminStudentController {

    private final StudentService studentService;

    @Operation(summary = "学生列表", description = "分页；可按状态筛选、按学号或姓名模糊搜索")
    @GetMapping
    public Result<PageResult<StudentVO>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize,
            @Parameter(description = "状态筛选 1启用 0停用") @RequestParam(required = false) Integer status,
            @Parameter(description = "学号或姓名关键字") @RequestParam(required = false) String keyword) {
        return Result.ok(studentService.page(pageNum, pageSize, status, keyword));
    }

    @Operation(summary = "新增学生",
            description = "学号即登录名（租户内唯一）。用户类型固定为学生，不接受传入。"
                    + "该账号首次登录会被要求修改初始口令")
    @PostMapping
    public Result<StudentVO> create(@Valid @RequestBody StudentCreateDTO dto) {
        return Result.ok(studentService.create(dto));
    }

    @Operation(summary = "修改学生信息",
            description = "改姓名 / 手机号 / 启停；口令留空表示不修改。停用会让该学生已登录的 token 立即失效")
    @PutMapping("/{id}")
    public Result<Void> update(@Parameter(description = "学生用户ID") @PathVariable long id,
                               @Valid @RequestBody StudentUpdateDTO dto) {
        studentService.update(id, dto);
        return Result.ok();
    }

    @Operation(summary = "重置学生口令",
            description = "单独入口，便于管理端做「一键重置」。重置后该学生下次登录仍需改密")
    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@Parameter(description = "学生用户ID") @PathVariable long id,
                                      @Valid @RequestBody StudentPasswordDTO dto) {
        studentService.resetPassword(id, dto.getPassword());
        return Result.ok();
    }

    @Operation(summary = "批量导入学生",
            description = "粘贴学号名单，每行一个（支持「学号,姓名」）。**幂等**：已存在的学号跳过而不是报错"
                    + "（学校补录时名单里必然带着上次已导的人），结果里会列出被跳过的学号。"
                    + "单次最多 500 条，导入的账号统一带初始口令且首次登录必须改密")
    @PostMapping("/import")
    public Result<StudentImportVO> importStudents(@Valid @RequestBody StudentImportDTO dto) {
        return Result.ok(studentService.importStudents(dto));
    }
}
