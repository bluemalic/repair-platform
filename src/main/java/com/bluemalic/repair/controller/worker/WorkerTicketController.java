package com.bluemalic.repair.controller.worker;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.common.WorkerTaskScope;
import com.bluemalic.repair.dto.TicketArriveDTO;
import com.bluemalic.repair.dto.TicketFinishDTO;
import com.bluemalic.repair.dto.TicketRejectDTO;
import com.bluemalic.repair.interceptor.RateLimit;
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
 * 维修工端工单接口。
 *
 * <p><b>可见范围</b>＝「我负责楼栋的工单」**或**「派给我的工单」（后者含后勤跨楼栋的紧急抽调），
 * 由数据权限拦截器注入；列表上的 {@code scope} 只是**业务视图**（我要干什么 / 这栋楼在修什么），
 * 越不过权限。规则见 `docs/01` §4.2。
 */
@Tag(name = "维修工端-工单")
@RestController
@RequestMapping("/api/worker/tickets")
@RequiredArgsConstructor
public class WorkerTicketController {

    private final TicketService ticketService;

    @Operation(summary = "我的任务",
            description = "两个视图：scope=mine（默认）只给派给我的单，默认状态为待接单/处理中/待验收；"
                    + "scope=building 给我负责楼栋的全部工单（含终态与别人负责的）。"
                    + "两者都可用 status 覆盖默认过滤。排序：紧急度高的置顶，同档内先来的在前")
    @SaCheckPermission("ticket:list:assigned")
    @GetMapping
    public Result<PageResult<TicketVO>> page(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") long pageNum,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long pageSize,
            @Parameter(description = "状态筛选") @RequestParam(required = false) Integer status,
            @Parameter(description = "视图：mine（派给我的，默认）/ building（我负责楼栋的全部）")
            @RequestParam(required = false) String scope) {
        return Result.ok(ticketService.pageWorkerTasks(pageNum, pageSize, status, WorkerTaskScope.of(scope)));
    }

    @Operation(summary = "工单详情")
    @SaCheckPermission("ticket:list:assigned")
    @GetMapping("/{id}")
    public Result<TicketDetailVO> detail(@Parameter(description = "工单ID") @PathVariable long id) {
        return Result.ok(ticketService.detail(id));
    }

    @Operation(summary = "接单", description = "待接单 → 处理中；被他人抢先返回 20003")
    @SaCheckPermission("ticket:accept")
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@Parameter(description = "工单ID") @PathVariable long id) {
        ticketService.accept(id);
        return Result.ok();
    }

    @Operation(summary = "驳回", description = "待接单/处理中 → 已驳回（可被重新派单）")
    @SaCheckPermission("ticket:reject")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@Parameter(description = "工单ID") @PathVariable long id,
                               @Valid @RequestBody TicketRejectDTO dto) {
        ticketService.rejectByWorker(id, dto);
        return Result.ok();
    }

    @Operation(summary = "扫报到场打卡",
            description = "校验报修码与工单房间一致；到场时间 = 响应时长的依据。"
                    + "请求体里的报修码同样按登录用户限流（默认 60 次/分钟），超限返回 10004")
    @SaCheckPermission("ticket:arrive")
    @RateLimit
    @PostMapping("/{id}/arrive")
    public Result<Void> arrive(@Parameter(description = "工单ID") @PathVariable long id,
                               @Valid @RequestBody TicketArriveDTO dto) {
        ticketService.arrive(id, dto);
        return Result.ok();
    }

    @Operation(summary = "完工上报", description = "处理中 → 待验收")
    @SaCheckPermission("ticket:finish")
    @PostMapping("/{id}/finish")
    public Result<Void> finish(@Parameter(description = "工单ID") @PathVariable long id,
                               @Valid @RequestBody TicketFinishDTO dto) {
        ticketService.finish(id, dto);
        return Result.ok();
    }
}
