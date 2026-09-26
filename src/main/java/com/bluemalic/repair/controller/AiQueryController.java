package com.bluemalic.repair.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.AiQueryDTO;
import com.bluemalic.repair.interceptor.RateLimit;
import com.bluemalic.repair.service.AiQueryService;
import com.bluemalic.repair.vo.AiQueryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 数据助手（docs/03 §5.6）。
 *
 * <p>只有后勤管理能用（权限码 {@code ai:query}），学生与维修工问不了数——这一条同时意味着
 * 不需要在安全网关上做角色维度的限制：后勤在本租户内本来就不受角色限制（ADR-003 的"落地时的修正"）。
 *
 * <p><b>限流用的是独立档位</b>（{@code @RateLimit(key = "ai")} → {@code repair.ai.rate-limit-max-requests}）：
 * 默认档是 60 次/分钟，对"每次调用都要花钱"的接口太松了。这个阈值是 AI 的**成本上限**，
 * 演示站上建议调到 3（那里账号的口令是公开的）。
 */
@Tag(name = "管理端-AI 数据助手", description = "自然语言问数：一句中文 → 数据 + 图 + 结论")
@SaCheckPermission("ai:query")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiQueryController {

    private final AiQueryService aiQueryService;

    @Operation(summary = "自然语言问数",
            description = "把一句中文变成一条只读查询并返回结果。生成的 SQL 一并返回，便于人工复核。"
                    + "失败时按原因给不同错误码：10002 未登录、40001 问题没能变成可执行的查询、"
                    + "40002 生成的 SQL 未通过安全校验（非单条 SELECT / 表不在白名单 / 查了敏感列等）、"
                    + "40003 查询超时、40004 模型或只读账号未配置")
    @RateLimit(key = "ai")
    @PostMapping("/query")
    public Result<AiQueryVO> query(@Valid @RequestBody AiQueryDTO dto) {
        return Result.ok(aiQueryService.ask(dto.getQuestion()));
    }
}
