package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.TicketDispatchDTO;
import com.bluemalic.repair.dto.TicketRejectDTO;
import com.bluemalic.repair.service.TicketService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.TicketDetailVO;
import com.bluemalic.repair.vo.TicketVO;
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
 * 后勤管理端工单接口。数据范围 = 本租户全部工单。
 */
@Tag(name = "后勤管理端-工单")
@RestController
@RequestMapping("/api/admin/tickets")
@RequiredArgsConstructor
public class AdminTicketController {

    private final TicketService ticketService;

    @Operation(summary = "全部工单", description = "支持状态 / 楼栋 / 类别筛选")
    @SaCheckPermission("ticket:list:all")
    @GetMapping
    public Result<PageResult<TicketVO>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize,
            @Parameter(description = "状态筛选") @RequestParam(required = false) Integer status,
            @Parameter(description = "楼栋筛选") @RequestParam(required = false) Long buildingId,
            @Parameter(description = "类别筛选") @RequestParam(required = false) Long categoryId) {
        return Result.ok(ticketService.page(pageNum, pageSize, status, buildingId, categoryId));
    }

    @Operation(summary = "工单详情", description = "含流转时间线与评价")
    @SaCheckPermission("ticket:list:all")
    @GetMapping("/{id}")
    public Result<TicketDetailVO> detail(@Parameter(description = "工单ID") @PathVariable long id) {
        return Result.ok(ticketService.detail(id));
    }

    @Operation(summary = "派单", description = "待派单/已驳回 → 待接单")
    @SaCheckPermission("ticket:dispatch")
    @PostMapping("/{id}/dispatch")
    public Result<Void> dispatch(@Parameter(description = "工单ID") @PathVariable long id,
                                 @Valid @RequestBody TicketDispatchDTO dto) {
        ticketService.dispatch(id, dto);
        return Result.ok();
    }

    @Operation(summary = "驳回", description = "待接单/处理中/待验收 → 已驳回")
    @SaCheckPermission("ticket:reject")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@Parameter(description = "工单ID") @PathVariable long id,
                               @Valid @RequestBody TicketRejectDTO dto) {
        ticketService.rejectByAdmin(id, dto);
        return Result.ok();
    }

    @Operation(summary = "关闭工单", description = "已完成 → 已关闭；超时自动关闭在 M3，这里是人工兜底")
    @SaCheckPermission("ticket:close")
    @PostMapping("/{id}/close")
    public Result<Void> close(@Parameter(description = "工单ID") @PathVariable long id) {
        ticketService.close(id);
        return Result.ok();
    }
}
