package com.bluemalic.repair.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.ai.AiQueryStreamer;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.dto.AiQueryDTO;
import com.bluemalic.repair.interceptor.RateLimit;
import com.bluemalic.repair.service.AiQueryService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.vo.AiQueryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 数据助手（docs/03 §5.6）。
 *
 * <p>只有后勤管理能用（权限码 {@code ai:query}），学生与维修工问不了数——这一条同时意味着
 * 不需要在安全网关上做角色维度的限制：后勤在本租户内本来就不受角色限制（ADR-003 的"落地时的修正"）。
 *
 * <p><b>限流用的是独立档位</b>（{@code @RateLimit(key = "ai")} → {@code repair.ai.rate-limit-max-requests}）：
 * 默认档是 60 次/分钟，对"每次调用都要花钱"的接口太松了。这个阈值是 AI 的**成本上限**，
 * 演示站上建议调到 3（那里账号的口令是公开的）。
 *
 * <p>两个接口跑的是同一条编排（{@link AiQueryService}），区别只是中间结果推不推给用户：
 * 一次性返回用 {@code POST /query}，流式用 {@code GET /query/stream}。**两个接口共用同一个限流档位**
 * ——它们花的是同一笔钱，各自一份计数等于把成本上限翻倍。
 */
@Tag(name = "管理端-AI 数据助手", description = "自然语言问数：一句中文 → 数据 + 图 + 结论")
@SaCheckPermission("ai:query")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiQueryController {

    private final AiQueryService aiQueryService;

    private final AiQueryStreamer aiQueryStreamer;

    private final CurrentTenantService currentTenantService;

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

    /**
     * 流式问数（SSE）。事件与载荷见 docs/03 §5.6：{@code sql} → {@code data} → {@code done}
     * （带结论与总耗时），出错则是一个 {@code error} 帧（带与同步接口相同的错误码）。
     *
     * <p>用 GET + query 参数而不是 POST + 请求体：问数是一次读取，参数只有一句话，GET 更贴它的语义；
     * 入参与 POST 共用 {@link AiQueryDTO}，所以校验规则（非空、200 字上限）也只有一份。
     * 代价是这句话会进 nginx 的 access log——它是给模型看的业务问题，不属于手机号 / 口令那类
     * 不该落盘的信息，可以接受。
     *
     * <p><b>同步的前置检查都在建流之前</b>：未登录、无权限、限流、参数不合法都还是普通的 JSON 错误
     * （与别的接口一致），只有**开始工作之后**的失败才走 {@code error} 帧。两条错误通道的分界写在这里，
     * 前端也照着它分：HTTP 层看 JSON，流里看 error 帧。
     */
    @Operation(summary = "自然语言问数（流式）",
            description = "SSE：SQL 生成后先推 sql、取到数据推 data、最后 done（结论 + 总耗时）。"
                    + "工作开始之后的失败通过 error 帧返回（code 与同步接口一致）。"
                    + "事件与载荷见 docs/03 §5.6")
    @RateLimit(key = "ai")
    @GetMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(@Valid @ModelAttribute AiQueryDTO dto) {
        // 租户必须在**请求线程**上取：流式的工作在别的线程跑，那里没有 Sa-Token 上下文。
        // 取不到就按普通 JSON 报未登录（不进流）——错误通道只留一条，前端不必处理两种。
        long tenantId = currentTenantService.requireTenantId();
        return aiQueryStreamer.start(dto.getQuestion(), tenantId);
    }
}
