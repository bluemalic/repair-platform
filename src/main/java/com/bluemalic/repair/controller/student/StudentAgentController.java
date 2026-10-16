package com.bluemalic.repair.controller.student;

import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.AgentIntakeDTO;
import com.bluemalic.repair.interceptor.RateLimit;
import com.bluemalic.repair.service.TicketAgentService;
import com.bluemalic.repair.vo.AgentIntakeVO;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端的报修受理 Agent。
 *
 * <p><b>权限码用 {@code ticket:create}</b>：它做的事就是"帮学生把报修说清楚"，与提交报修同一件事；
 * 另造一个 {@code agent:xxx} 权限码只会让"谁能用受理助手"多一个需要维护的判断点。
 *
 * <p><b>限流档位独立于问数</b>：都用 `key = "ai"` 这一档，因为消耗的性质相同（调模型要花钱），
 * 没必要为两个入口各配一套阈值。
 *
 * <p>**本接口不写库**：返回的是草稿，落单由前端调 {@code POST /api/student/tickets}（ADR-017）。
 */
@Tag(name = "学生端-报修受理 Agent", description = "多轮对话补全报修信息，产出工单草稿（不落库）")
@RestController
@RequestMapping("/api/student/agent")
@RequiredArgsConstructor
public class StudentAgentController {

    private final TicketAgentService ticketAgentService;

    @Operation(summary = "报修受理（一轮对话）",
            description = "传用户这一轮说的话（可带扫码得到的 repairCode 作为位置上下文）。"
                    + "返回追问或草稿：draft 字段与提交报修接口的入参一一对应，ready 为 true 时可直接提交")
    @SaCheckPermission("ticket:create")
    @RateLimit(key = "ai")
    @PostMapping("/intake")
    public Result<AgentIntakeVO> intake(@Valid @RequestBody AgentIntakeDTO dto) {
        return Result.ok(ticketAgentService.intake(dto));
    }
}
