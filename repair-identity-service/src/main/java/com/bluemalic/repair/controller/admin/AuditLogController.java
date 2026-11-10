package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.service.AuditService;
import com.bluemalic.repair.vo.AuditLogVO;
import com.bluemalic.repair.vo.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 操作审计日志（`docs/01` §4.4）。
 *
 * <p>只有后勤管理能看（权限码 {@code audit:list}），**只看本租户**——
 * {@code audit_log} 不在数据权限拦截器的名单里，所以租户条件是在 service 里显式写的（AGENTS §5.6）。
 *
 * <p>覆盖范围是"账号与基础数据的写操作"：工单流转去工单的时间线看（那里更细），
 * 读操作不记（那是访问日志）。
 */
@Tag(name = "管理端-操作日志", description = "谁在什么时候改了账号或基础数据")
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    @Operation(summary = "操作日志",
            description = "本租户的账号与基础数据写操作，时间倒序。可按动作码、操作人姓名、日期区间筛选。"
                    + "**默认不含登录事件**（这个页面的主查询是「谁改了东西」，登录记录会占绝大多数）："
                    + "要看登录就把 includeLogin 传 true，或直接按 LOGIN_SUCCESS / LOGIN_FAILED / "
                    + "LOGIN_DISABLED 筛——显式指定动作时 includeLogin 不起作用")
    @SaCheckPermission("audit:list")
    @GetMapping
    public Result<PageResult<AuditLogVO>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "20") long pageSize,
            @Parameter(description = "动作码，如 WORKER_CREATE / LOGIN_FAILED")
            @RequestParam(required = false) String action,
            @Parameter(description = "操作人姓名关键字") @RequestParam(required = false) String operatorKeyword,
            @Parameter(description = "起始日期 yyyy-MM-dd（含）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 yyyy-MM-dd（含）")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "是否包含登录事件，默认 false")
            @RequestParam(defaultValue = "false") boolean includeLogin) {
        return Result.ok(auditService.page(pageNum, pageSize, action, operatorKeyword,
                startDate, endDate, includeLogin));
    }
}
