package com.bluemalic.repair.controller.admin;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.interceptor.RateLimit;
import com.bluemalic.repair.service.TicketAgentService;
import com.bluemalic.repair.vo.TriageSuggestionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工单的**智能分诊建议**（阶段 3.3 从 {@code AdminTicketController} 拆出来）。
 *
 * <p><b>为什么单独一个类</b>：这个端点属于 ai 域（调模型、烧钱、有 @RateLimit），而工单状态机与派单
 * 已经搬进 `repair-ticket-service`。留在一起会让 ticket 模块反向依赖 ai 的实现——
 * 拆开之后 ticket 只认表与状态机，ai 只读工单（走 domain 的 mapper）。
 *
 * <p>路径没变：仍然是 `POST /api/admin/tickets/{id}/triage`，前端零改动。
 */
@Tag(name = "后勤管理端-工单智能分诊", description = "对一张工单给建议类别 / 紧急度，只给建议不写库（ADR-017）")
@RestController
@RequestMapping("/api/admin/tickets")
@RequiredArgsConstructor
public class AdminTicketTriageController {

    private final TicketAgentService ticketAgentService;

    @Operation(summary = "智能分诊建议",
            description = "对一张工单给出建议类别 / 紧急度与一句理由。**只给建议，不写库**——"
                    + "采纳与否由后勤决定（ADR-017）")
    @SaCheckPermission("ticket:dispatch")
    @RateLimit(key = "ai")
    @PostMapping("/{id}/triage")
    public Result<TriageSuggestionVO> triage(@Parameter(description = "工单ID") @PathVariable long id) {
        return Result.ok(ticketAgentService.triage(id));
    }
}
