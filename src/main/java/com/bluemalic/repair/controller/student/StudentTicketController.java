package com.bluemalic.repair.controller.student;

import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.TicketCreateDTO;
import com.bluemalic.repair.dto.TicketEvaluateDTO;
import com.bluemalic.repair.service.TicketService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.TicketDetailVO;
import com.bluemalic.repair.vo.TicketVO;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端工单接口。谁能看到哪些单由数据权限拦截器保证（学生 → 自己的单）。
 */
@Tag(name = "学生端-工单")
@RestController
@RequestMapping("/api/student/tickets")
@RequiredArgsConstructor
public class StudentTicketController {

    private final TicketService ticketService;

    @Operation(summary = "提交报修")
    @SaCheckPermission("ticket:create")
    @PostMapping
    public Result<TicketVO> submit(@Valid @RequestBody TicketCreateDTO dto) {
        return Result.ok(ticketService.submit(dto));
    }

    @Operation(summary = "我的工单", description = "只返回当前学生自己的工单（数据权限拦截器保证）")
    @SaCheckPermission("ticket:list:self")
    @GetMapping
    public Result<PageResult<TicketVO>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize,
            @Parameter(description = "状态筛选") @RequestParam(required = false) Integer status) {
        return Result.ok(ticketService.page(pageNum, pageSize, status, null, null));
    }

    @Operation(summary = "工单详情")
    @SaCheckPermission("ticket:list:self")
    @GetMapping("/{id}")
    public Result<TicketDetailVO> detail(@Parameter(description = "工单ID") @PathVariable long id) {
        return Result.ok(ticketService.detail(id));
    }

    @Operation(summary = "撤销工单", description = "仅待派单状态可撤销")
    @SaCheckPermission("ticket:cancel")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@Parameter(description = "工单ID") @PathVariable long id) {
        ticketService.cancel(id);
        return Result.ok();
    }

    @Operation(summary = "验收评价", description = "待验收状态下评价，工单转为已完成")
    @SaCheckPermission("ticket:evaluate")
    @PostMapping("/{id}/evaluate")
    public Result<Void> evaluate(@Parameter(description = "工单ID") @PathVariable long id,
                                 @Valid @RequestBody TicketEvaluateDTO dto) {
        ticketService.evaluate(id, dto);
        return Result.ok();
    }
}
