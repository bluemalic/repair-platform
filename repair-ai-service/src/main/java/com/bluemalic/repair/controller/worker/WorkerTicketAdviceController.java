package com.bluemalic.repair.controller.worker;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.interceptor.RateLimit;
import com.bluemalic.repair.service.KnowledgeAdviceService;
import com.bluemalic.repair.vo.KnowledgeAdviceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维修工的 **AI 排查建议**（阶段 3.3 从 {@code WorkerTicketController} 拆出来）。
 *
 * <p><b>为什么单独一个类</b>：它属于 ai 域（检索知识库 + 调模型 + 有 @RateLimit），
 * 而工单状态机已搬进 `repair-ticket-service`。拆开之后 ticket 模块不再引用 ai 的实现，
 * 依赖方向保持 ticket ← ai（ai 只读工单表）。
 *
 * <p>路径没变：`POST /api/worker/tickets/{id}/advice`；网关有一条更长前缀的路由排在其父路径之前。
 */
@Tag(name = "维修工端-AI 排查建议", description = "按工单描述检索维修知识库，给出带引用的排查步骤（ADR-018）")
@RestController
@RequestMapping("/api/worker/tickets")
@RequiredArgsConstructor
public class WorkerTicketAdviceController {

    private final KnowledgeAdviceService knowledgeAdviceService;

    @Operation(summary = "AI 排查建议",
            description = "按工单的描述与类别检索本租户维修知识库，给出**带引用来源**的排查步骤（ADR-018）。"
                    + "**不写库**；知识库里没有相关条目时不调模型，直接如实说没有。"
                    + "权限沿用「能看这张单才能要建议」（与分诊用 ticket:dispatch 是同一个口径）")
    @SaCheckPermission("ticket:list:assigned")
    @RateLimit(key = "ai")
    @PostMapping("/{id}/advice")
    public Result<KnowledgeAdviceVO> advice(@Parameter(description = "工单ID") @PathVariable long id) {
        return Result.ok(knowledgeAdviceService.advice(id));
    }
}
